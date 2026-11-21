package com.ject.ziper.domain.file.repository;

public interface ProductCardImageUpdater {
	/**
	 * Attaches the mirrored file only if the card still exists (not soft-deleted)
	 * and its current {@code image_url} still equals the URL the job was created
	 * for.
	 *
	 * @return whether the card was updated
	 */
	boolean markMirrorReady(Long cardId, String expectedImageUrl, Long fileId);

	/**
	 * Records {@code image_status = 'FAILED'} on the card, keeping its original
	 * {@code image_url}, only if the card still exists (not soft-deleted) and its
	 * current {@code image_url} still equals the URL the failed job was created
	 * for.
	 *
	 * @return whether the card was updated
	 */
	boolean markMirrorFailed(Long cardId, String expectedImageUrl);
}
