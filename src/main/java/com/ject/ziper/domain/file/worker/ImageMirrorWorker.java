package com.ject.ziper.domain.file.worker;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ject.ziper.domain.file.client.ExternalImageFetcher;
import com.ject.ziper.domain.file.client.ExternalImageFetcher.FetchResult;
import com.ject.ziper.domain.file.entity.FileAsset;
import com.ject.ziper.domain.file.entity.ImageMirrorJob;
import com.ject.ziper.domain.file.entity.ImageMirrorJobStatus;
import com.ject.ziper.domain.file.repository.ImageMirrorJobRepository;
import com.ject.ziper.domain.file.repository.ProductCardImageUpdater;
import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.storage.StorageProperties;

@Component
public class ImageMirrorWorker {
	private static final Logger log = LoggerFactory.getLogger(ImageMirrorWorker.class);
	private static final String MAX_ATTEMPTS_EXCEEDED = "MAX_ATTEMPTS_EXCEEDED";

	private final ImageMirrorJobRepository jobRepository;
	private final ExternalImageFetcher imageFetcher;
	private final FileImageService fileImageService;
	private final ProductCardImageUpdater productCardImageUpdater;
	private final StorageProperties properties;
	private final TransactionTemplate transactionTemplate;
	private final ExecutorService executor;
	private final Clock clock;
	private final AtomicBoolean drainRunning = new AtomicBoolean(false);
	private final AtomicBoolean wakeupRequested = new AtomicBoolean(false);

	@Autowired
	public ImageMirrorWorker(ImageMirrorJobRepository jobRepository, ExternalImageFetcher imageFetcher,
			FileImageService fileImageService, ProductCardImageUpdater productCardImageUpdater,
			StorageProperties properties, PlatformTransactionManager transactionManager,
			@Qualifier("imageMirrorExecutor") ExecutorService executor) {
		this(jobRepository, imageFetcher, fileImageService, productCardImageUpdater, properties, transactionManager,
				executor, Clock.systemUTC());
	}

	public ImageMirrorWorker(ImageMirrorJobRepository jobRepository, ExternalImageFetcher imageFetcher,
			FileImageService fileImageService, ProductCardImageUpdater productCardImageUpdater,
			StorageProperties properties, PlatformTransactionManager transactionManager, ExecutorService executor,
			Clock clock) {
		this.jobRepository = jobRepository;
		this.imageFetcher = imageFetcher;
		this.fileImageService = fileImageService;
		this.productCardImageUpdater = productCardImageUpdater;
		this.properties = properties;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.executor = executor;
		this.clock = clock;
	}

	public void wakeup() {
		if (!properties.workerEnabled() || !properties.mirroringEnabled()) {
			return;
		}
		wakeupRequested.set(true);
		if (!drainRunning.compareAndSet(false, true)) {
			return;
		}
		try {
			executor.execute(this::drainLoop);
		} catch (RejectedExecutionException exception) {
			drainRunning.set(false);
			log.warn("Image mirror worker wakeup rejected; pending jobs will be claimed by scheduled polling");
		}
	}

	@Scheduled(fixedDelayString = "${app.storage.mirror-poll-interval-ms:5000}")
	public void pollPendingJobs() {
		if (!properties.workerEnabled() || !properties.mirroringEnabled()) {
			return;
		}
		processPendingJobs();
	}

	public int processPendingJobs() {
		if (!properties.mirroringEnabled()) {
			return 0;
		}
		ClaimResult claim = claimBatch();
		for (ImageMirrorJob job : claim.jobs()) {
			processSingleJob(job);
		}
		return claim.totalClaimed();
	}

	private void drainLoop() {
		try {
			while (wakeupRequested.getAndSet(false)) {
				int processed;
				do {
					processed = processPendingJobs();
				} while (processed >= properties.mirrorBatchSize());
			}
		} catch (RuntimeException exception) {
			log.warn("Image mirror worker drain loop failed", exception);
		} finally {
			drainRunning.set(false);
			if (wakeupRequested.get()) {
				wakeup();
			}
		}
	}

	/**
	 * Claims due PENDING jobs and PROCESSING jobs whose lease expired (the worker
	 * died after claiming them). A reclaimed job counts as another attempt, so a
	 * job that keeps killing its worker ends in FAILED instead of looping forever.
	 */
	private ClaimResult claimBatch() {
		ClaimResult claimed = transactionTemplate.execute(status -> {
			Instant now = clock.instant();
			Instant lockExpiredBefore = now.minusSeconds(properties.mirrorLockTimeoutSeconds());
			List<ImageMirrorJob> due = jobRepository.findClaimableForUpdateSkipLocked(now, lockExpiredBefore,
					properties.mirrorBatchSize());
			List<ImageMirrorJob> toProcess = new ArrayList<>();
			for (ImageMirrorJob job : due) {
				if (job.getAttemptCount() >= properties.mirrorMaxAttempts()) {
					job.markFailed(MAX_ATTEMPTS_EXCEEDED, now);
					productCardImageUpdater.markMirrorFailed(job.getCardId());
				} else {
					job.markProcessing(now);
					toProcess.add(job);
				}
			}
			jobRepository.saveAll(due);
			return new ClaimResult(toProcess, due.size());
		});
		return claimed == null ? new ClaimResult(List.of(), 0) : claimed;
	}

	private void processSingleJob(ImageMirrorJob job) {
		try {
			// A batch is claimed at once, so renew the lease right before starting this
			// job. 0 rows: the job was
			// canceled or finished by someone else, nothing to do.
			Integer renewed = transactionTemplate
					.execute(status -> jobRepository.refreshLease(job.getId(), clock.instant()));
			if (renewed == null || renewed == 0) {
				return;
			}
			if (properties.isHostDisabled(job.getHost())) {
				finishFailed(job, "HOST_MIRRORING_DISABLED", false);
				return;
			}
			// A previous attempt may have stored the thumbnail and died before completing
			// the job: confirm the
			// stored result instead of downloading again.
			Optional<FileAsset> previous = fileImageService.findMirroredFile(job.getId());
			if (previous.isPresent()) {
				completeReady(job, previous.get().getId());
				return;
			}
			FetchResult fetched = imageFetcher.fetch(job.getSourceUrl(), job.getSourceUrlHash(), job.getShop());
			if (!fetched.success()) {
				finishFailed(job, fetched.errorReason(), fetched.transientFailure());
				return;
			}
			FileAsset asset = fileImageService.storeMirroredThumbnail(job.getId(), job.getUserId(), fetched.bytes(),
					fetched.contentType(), deriveFilename(job.getSourceUrl()));
			completeReady(job, asset.getId());
		} catch (BusinessException exception) {
			log.warn("Image mirror job failed validation jobId={} cardId={} code={}", job.getId(), job.getCardId(),
					exception.errorCode().name());
			finishFailed(job, exception.errorCode().name(), false);
		} catch (RuntimeException exception) {
			// Storage or DB trouble. Re-processing is idempotent (deterministic key, stored
			// result is reused), so
			// it is retried with backoff up to the attempt limit.
			log.warn("Image mirror job failed unexpectedly jobId={} cardId={}", job.getId(), job.getCardId(),
					exception);
			finishFailed(job, "MIRROR_PROCESSING_ERROR", true);
		}
	}

	/**
	 * Attaches the file to the card only if the job is still ours (PROCESSING, not
	 * canceled or reclaimed) and the card still shows the image this job was
	 * created for. Otherwise the job is closed as CANCELED and the stored thumbnail
	 * stays as an unreferenced, owner-tagged file for the orphan cleanup (JEC-10).
	 */
	private void completeReady(ImageMirrorJob job, Long fileId) {
		transactionTemplate.executeWithoutResult(status -> {
			Optional<ImageMirrorJob> locked = jobRepository.findByIdForUpdate(job.getId());
			if (locked.isEmpty() || locked.get().getStatus() != ImageMirrorJobStatus.PROCESSING) {
				log.info("Image mirror job no longer active; leaving card untouched jobId={}", job.getId());
				return;
			}
			ImageMirrorJob current = locked.get();
			Instant now = clock.instant();
			if (productCardImageUpdater.markMirrorReady(current.getCardId(), current.getSourceUrl(), fileId)) {
				current.markReady(now);
			} else {
				current.markCanceled("CARD_IMAGE_CHANGED", now);
			}
			jobRepository.save(current);
		});
	}

	/**
	 * Transient failures return to PENDING with exponential backoff until the
	 * attempt limit; permanent ones (and exhausted retries) end as FAILED. An open
	 * host circuit also waits at least the circuit cooldown.
	 */
	private void finishFailed(ImageMirrorJob job, String errorReason, boolean transientFailure) {
		transactionTemplate.executeWithoutResult(status -> {
			Optional<ImageMirrorJob> locked = jobRepository.findByIdForUpdate(job.getId());
			if (locked.isEmpty() || locked.get().getStatus() != ImageMirrorJobStatus.PROCESSING) {
				return;
			}
			ImageMirrorJob current = locked.get();
			Instant now = clock.instant();
			if (transientFailure && current.getAttemptCount() < properties.mirrorMaxAttempts()) {
				current.scheduleRetry(errorReason, now.plus(retryDelay(current.getAttemptCount(), errorReason)), now);
			} else {
				current.markFailed(errorReason, now);
				productCardImageUpdater.markMirrorFailed(current.getCardId());
			}
			jobRepository.save(current);
		});
	}

	private Duration retryDelay(int attemptCount, String errorReason) {
		long base = properties.mirrorRetryBaseDelaySeconds();
		long max = properties.mirrorRetryMaxDelaySeconds();
		long delay = base;
		for (int i = 1; i < attemptCount && delay < max; i++) {
			delay = Math.min(max, delay * 2);
		}
		if ("HOST_CIRCUIT_OPEN".equals(errorReason)) {
			delay = Math.max(delay, properties.mirrorCircuitOpenSeconds());
		}
		return Duration.ofSeconds(delay);
	}

	private record ClaimResult(List<ImageMirrorJob> jobs, int totalClaimed) {
	}

	private static String deriveFilename(String sourceUrl) {
		try {
			String path = URI.create(sourceUrl).getPath();
			if (path != null && !path.isBlank()) {
				int lastSlash = path.lastIndexOf('/');
				String segment = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
				if (!segment.isBlank()) {
					return segment;
				}
			}
		} catch (IllegalArgumentException exception) {
			return "mirrored.webp";
		}
		return "mirrored.webp";
	}
}
