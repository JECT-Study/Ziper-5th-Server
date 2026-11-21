package com.ject.ziper.global.storage;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;

import org.slf4j.LoggerFactory;

public interface ObjectStorageClient {
	void putObject(String storageKey, byte[] content, String contentType);

	String createSignedUrl(String storageKey, Duration expiresIn);

	/**
	 * Signs many keys with one storage call. Keys the storage reports as failed
	 * (for example a missing object) are absent from the result; a failure of the
	 * call as a whole propagates as an exception.
	 */
	Map<String, String> createSignedUrls(Collection<String> storageKeys, Duration expiresIn);

	void deleteObject(String storageKey);

	/**
	 * Compensation helper for a failed follow-up step: deletes the object and logs
	 * (instead of throwing) if the delete fails, so the caller can propagate the
	 * original failure.
	 */
	default void deleteQuietly(String storageKey) {
		try {
			deleteObject(storageKey);
		} catch (RuntimeException exception) {
			LoggerFactory.getLogger(ObjectStorageClient.class).warn(
					"Compensating object delete failed; object may be orphaned storageKey={}", storageKey, exception);
		}
	}
}
