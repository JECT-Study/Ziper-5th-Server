package com.ject.ziper.global.storage;

import java.time.Duration;

public interface ObjectStorageClient {
	void putObject(String storageKey, byte[] content, String contentType);

	String createSignedUrl(String storageKey, Duration expiresIn);

	void deleteObject(String storageKey);
}
