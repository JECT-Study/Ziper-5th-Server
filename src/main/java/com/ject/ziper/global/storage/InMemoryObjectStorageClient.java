package com.ject.ziper.global.storage;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemoryObjectStorageClient implements ObjectStorageClient {
	private final ConcurrentMap<String, StoredObject> objects = new ConcurrentHashMap<>();

	@Override
	public void putObject(String storageKey, byte[] content, String contentType) {
		if (storageKey == null || storageKey.isBlank()) {
			throw new IllegalArgumentException("storageKey must not be blank");
		}
		if (content == null) {
			throw new IllegalArgumentException("content must not be null");
		}
		objects.put(storageKey, new StoredObject(Arrays.copyOf(content, content.length), contentType));
	}

	@Override
	public String createSignedUrl(String storageKey, Duration expiresIn) {
		if (storageKey == null || storageKey.isBlank()) {
			throw new IllegalArgumentException("storageKey must not be blank");
		}
		long seconds = expiresIn == null ? 900L : Math.max(1L, expiresIn.toSeconds());
		return "https://storage.local/signed/" + storageKey + "?expiresIn=" + seconds;
	}

	@Override
	public Map<String, String> createSignedUrls(Collection<String> storageKeys, Duration expiresIn) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String storageKey : storageKeys) {
			result.put(storageKey, createSignedUrl(storageKey, expiresIn));
		}
		return result;
	}

	@Override
	public void deleteObject(String storageKey) {
		if (storageKey == null || storageKey.isBlank()) {
			return;
		}
		objects.remove(storageKey);
	}

	public Optional<StoredObject> getObject(String storageKey) {
		StoredObject stored = objects.get(storageKey);
		if (stored == null) {
			return Optional.empty();
		}
		return Optional
				.of(new StoredObject(Arrays.copyOf(stored.content(), stored.content().length), stored.contentType()));
	}

	public boolean contains(String storageKey) {
		return objects.containsKey(storageKey);
	}

	public int size() {
		return objects.size();
	}

	public void clear() {
		objects.clear();
	}

	public record StoredObject(byte[] content, String contentType) {
	}
}
