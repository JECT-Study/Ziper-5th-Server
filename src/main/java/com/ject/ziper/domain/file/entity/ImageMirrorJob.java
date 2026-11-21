package com.ject.ziper.domain.file.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "image_mirror_jobs", schema = "ziper")
public class ImageMirrorJob {
	private static final int MAX_ERROR_LENGTH = 512;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "card_id", nullable = false)
	private Long cardId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "source_url", nullable = false, columnDefinition = "TEXT")
	private String sourceUrl;

	@Column(name = "source_url_hash", nullable = false, length = 64)
	private String sourceUrlHash;

	@Column(name = "host", nullable = false, length = 255)
	private String host;

	@Column(name = "shop", length = 100)
	private String shop;

	@Column(name = "priority", nullable = false)
	private short priority;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private ImageMirrorJobStatus status;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "last_error", length = 512)
	private String lastError;

	@Column(name = "locked_at")
	private Instant lockedAt;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ImageMirrorJob() {
	}

	private ImageMirrorJob(Long cardId, Long userId, String sourceUrl, String sourceUrlHash, String host, String shop,
			short priority, Instant now) {
		this.cardId = cardId;
		this.userId = userId;
		this.sourceUrl = sourceUrl;
		this.sourceUrlHash = sourceUrlHash;
		this.host = host;
		this.shop = shop;
		this.priority = priority;
		this.status = ImageMirrorJobStatus.PENDING;
		this.attemptCount = 0;
		this.nextAttemptAt = now;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static ImageMirrorJob createPending(Long cardId, Long userId, String sourceUrl, String sourceUrlHash,
			String host, String shop, short priority, Instant now) {
		return new ImageMirrorJob(cardId, userId, sourceUrl, sourceUrlHash, host, shop, priority, now);
	}

	public void markProcessing(Instant now) {
		this.status = ImageMirrorJobStatus.PROCESSING;
		this.attemptCount += 1;
		this.lockedAt = now;
		this.updatedAt = now;
	}

	public void markReady(Instant now) {
		this.status = ImageMirrorJobStatus.READY;
		this.lastError = null;
		this.updatedAt = now;
	}

	public void markFailed(String errorReason, Instant now) {
		this.status = ImageMirrorJobStatus.FAILED;
		this.lastError = truncateError(errorReason);
		this.lockedAt = null;
		this.updatedAt = now;
	}

	/**
	 * Returns the job to the queue; the poller skips it until
	 * {@code nextAttemptAt}.
	 */
	public void scheduleRetry(String errorReason, Instant nextAttemptAt, Instant now) {
		this.status = ImageMirrorJobStatus.PENDING;
		this.lastError = truncateError(errorReason);
		this.lockedAt = null;
		this.nextAttemptAt = nextAttemptAt;
		this.updatedAt = now;
	}

	public void markCanceled(String reason, Instant now) {
		this.status = ImageMirrorJobStatus.CANCELED;
		this.lastError = truncateError(reason);
		this.lockedAt = null;
		this.updatedAt = now;
	}

	private static String truncateError(String errorReason) {
		if (errorReason == null || errorReason.isBlank()) {
			return "MIRROR_FAILED";
		}
		String trimmed = errorReason.trim();
		return trimmed.length() <= MAX_ERROR_LENGTH ? trimmed : trimmed.substring(0, MAX_ERROR_LENGTH);
	}

	public Long getId() {
		return id;
	}

	public Long getCardId() {
		return cardId;
	}

	public Long getUserId() {
		return userId;
	}

	public String getSourceUrl() {
		return sourceUrl;
	}

	public String getSourceUrlHash() {
		return sourceUrlHash;
	}

	public String getHost() {
		return host;
	}

	public String getShop() {
		return shop;
	}

	public short getPriority() {
		return priority;
	}

	public ImageMirrorJobStatus getStatus() {
		return status;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public String getLastError() {
		return lastError;
	}

	public Instant getLockedAt() {
		return lockedAt;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
