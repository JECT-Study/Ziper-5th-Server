package com.ject.ziper.domain.file.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ject.ziper.domain.file.client.ExternalImageFetcher;
import com.ject.ziper.domain.file.entity.ImageMirrorJob;
import com.ject.ziper.domain.file.repository.ImageMirrorJobRepository;
import com.ject.ziper.domain.file.worker.ImageMirrorWorker;
import com.ject.ziper.global.storage.StorageProperties;

@Service
public class ImageMirrorService {
	public static final short DEFAULT_PRIORITY = 0;
	public static final short EXPIRING_URL_PRIORITY = 10;

	private final ImageMirrorJobRepository jobRepository;
	private final StorageProperties properties;
	private final ImageMirrorWorker imageMirrorWorker;
	private final Clock clock;

	@Autowired
	public ImageMirrorService(ImageMirrorJobRepository jobRepository, StorageProperties properties,
			ImageMirrorWorker imageMirrorWorker) {
		this(jobRepository, properties, imageMirrorWorker, Clock.systemUTC());
	}

	public ImageMirrorService(ImageMirrorJobRepository jobRepository, StorageProperties properties,
			ImageMirrorWorker imageMirrorWorker, Clock clock) {
		this.jobRepository = jobRepository;
		this.properties = properties;
		this.imageMirrorWorker = imageMirrorWorker;
		this.clock = clock;
	}

	@Transactional
	public boolean enqueueMirrorJob(Long cardId, Long userId, String sourceUrl, String shop) {
		if (!properties.mirroringEnabled() || cardId == null || userId == null || sourceUrl == null
				|| sourceUrl.isBlank()) {
			return false;
		}
		String trimmedUrl = sourceUrl.trim();
		Optional<String> hostOptional = ExternalImageFetcher.extractHttpsHost(trimmedUrl);
		if (hostOptional.isEmpty()) {
			return false;
		}
		String host = hostOptional.get();
		if (properties.isHostDisabled(host)) {
			return false;
		}

		String sourceUrlHash = ExternalImageFetcher.sha256Hex(trimmedUrl);
		short priority = ExternalImageFetcher.isExpiringSignedUrl(trimmedUrl)
				? EXPIRING_URL_PRIORITY
				: DEFAULT_PRIORITY;
		Instant now = clock.instant();
		String normalizedShop = shop == null || shop.isBlank() ? null : shop.trim();

		ImageMirrorJob job = ImageMirrorJob.createPending(cardId, userId, trimmedUrl, sourceUrlHash, host,
				normalizedShop, priority, now);
		jobRepository.save(job);
		schedulePostCommitWakeup();
		return true;
	}

	/**
	 * Cancels the card's PENDING and PROCESSING jobs. Call it in the same
	 * transaction that edits the card's image or deletes the card, so a
	 * late-finishing worker cannot attach a stale image. Returns the number of jobs
	 * canceled.
	 */
	@Transactional
	public int cancelPendingMirrorJobs(long cardId) {
		return jobRepository.cancelActiveByCardId(cardId, clock.instant());
	}

	private void schedulePostCommitWakeup() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					imageMirrorWorker.wakeup();
				}
			});
		} else {
			imageMirrorWorker.wakeup();
		}
	}
}
