package com.ject.ziper.domain.file.worker;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
import com.ject.ziper.domain.file.repository.ImageMirrorJobRepository;
import com.ject.ziper.domain.file.repository.ProductCardImageUpdater;
import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.storage.StorageProperties;

@Component
public class ImageMirrorWorker {
	private static final Logger log = LoggerFactory.getLogger(ImageMirrorWorker.class);

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
		List<ImageMirrorJob> claimed = claimBatch();
		for (ImageMirrorJob job : claimed) {
			processSingleJob(job);
		}
		return claimed.size();
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

	private List<ImageMirrorJob> claimBatch() {
		List<ImageMirrorJob> claimed = transactionTemplate.execute(status -> {
			Instant now = clock.instant();
			List<ImageMirrorJob> pending = jobRepository.findPendingForUpdateSkipLocked(now,
					properties.mirrorBatchSize());
			for (ImageMirrorJob job : pending) {
				job.markProcessing(now);
			}
			return jobRepository.saveAll(pending);
		});
		return claimed == null ? List.of() : claimed;
	}

	private void processSingleJob(ImageMirrorJob job) {
		try {
			if (properties.isHostDisabled(job.getHost())) {
				completeFailed(job.getId(), job.getCardId(), "HOST_MIRRORING_DISABLED");
				return;
			}
			FetchResult fetched = imageFetcher.fetch(job.getSourceUrl(), job.getSourceUrlHash(), job.getShop());
			if (!fetched.success()) {
				completeFailed(job.getId(), job.getCardId(), fetched.errorReason());
				return;
			}
			FileAsset asset = fileImageService.storeMirroredThumbnail(fetched.bytes(), fetched.contentType(),
					deriveFilename(job.getSourceUrl()));
			completeReady(job.getId(), job.getCardId(), asset.getId());
		} catch (BusinessException exception) {
			log.warn("Image mirror job failed validation jobId={} cardId={} code={}", job.getId(), job.getCardId(),
					exception.errorCode().name());
			completeFailed(job.getId(), job.getCardId(), exception.errorCode().name());
		} catch (RuntimeException exception) {
			log.warn("Image mirror job failed unexpectedly jobId={} cardId={}", job.getId(), job.getCardId(),
					exception);
			completeFailed(job.getId(), job.getCardId(), "MIRROR_PROCESSING_ERROR");
		}
	}

	private void completeReady(Long jobId, Long cardId, Long fileId) {
		transactionTemplate.executeWithoutResult(status -> {
			productCardImageUpdater.markMirrorReady(cardId, fileId);
			jobRepository.findById(jobId).ifPresent(job -> {
				job.markReady(clock.instant());
				jobRepository.save(job);
			});
		});
	}

	private void completeFailed(Long jobId, Long cardId, String errorReason) {
		transactionTemplate.executeWithoutResult(status -> {
			productCardImageUpdater.markMirrorFailed(cardId);
			jobRepository.findById(jobId).ifPresent(job -> {
				job.markFailed(errorReason, clock.instant());
				jobRepository.save(job);
			});
		});
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
