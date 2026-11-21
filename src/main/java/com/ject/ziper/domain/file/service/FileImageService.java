package com.ject.ziper.domain.file.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ject.ziper.domain.file.dto.ImageUploadResponse;
import com.ject.ziper.domain.file.entity.FileAsset;
import com.ject.ziper.domain.file.repository.FileAssetRepository;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.storage.ObjectStorageClient;
import com.ject.ziper.global.storage.StorageProperties;
import com.ject.ziper.global.storage.WebpThumbnailProcessor;
import com.ject.ziper.global.storage.WebpThumbnailProcessor.ProcessedThumbnail;

@Service
public class FileImageService {
	private static final DateTimeFormatter KEY_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM")
			.withZone(ZoneOffset.UTC);
	private static final int MAX_ORIGINAL_FILENAME_LENGTH = 255;

	private final ObjectStorageClient objectStorageClient;
	private final WebpThumbnailProcessor thumbnailProcessor;
	private final FileAssetRepository fileAssetRepository;
	private final StorageProperties properties;
	private final Clock clock;

	@Autowired
	public FileImageService(ObjectStorageClient objectStorageClient, WebpThumbnailProcessor thumbnailProcessor,
			FileAssetRepository fileAssetRepository, StorageProperties properties) {
		this(objectStorageClient, thumbnailProcessor, fileAssetRepository, properties, Clock.systemUTC());
	}

	public FileImageService(ObjectStorageClient objectStorageClient, WebpThumbnailProcessor thumbnailProcessor,
			FileAssetRepository fileAssetRepository, StorageProperties properties, Clock clock) {
		this.objectStorageClient = objectStorageClient;
		this.thumbnailProcessor = thumbnailProcessor;
		this.fileAssetRepository = fileAssetRepository;
		this.properties = properties;
		this.clock = clock;
	}

	/**
	 * Not transactional on purpose: the Supabase calls must not hold a DB
	 * connection. If persisting the metadata fails after the object upload, the
	 * freshly uploaded object is deleted again.
	 */
	public ImageUploadResponse uploadDirectImage(long ownerUserId, byte[] rawBytes, String originalFilename,
			String contentType, long declaredSize) {
		if (declaredSize > properties.maxFileSizeBytes()) {
			throw new BusinessException(ErrorCode.IMAGE_FILE_TOO_LARGE);
		}
		if (rawBytes == null || rawBytes.length == 0) {
			throw new BusinessException(ErrorCode.INVALID_IMAGE_FILE);
		}
		Instant now = clock.instant();
		FileAsset saved = transcodeAndStore(generateStorageKey(now), ownerUserId, rawBytes, contentType,
				originalFilename, now, true);
		String signedUrl = createSignedUrl(saved.getStorageKey());
		return new ImageUploadResponse(saved.getId(), signedUrl, saved.getContentType(), saved.getFileSize());
	}

	/**
	 * Stores the thumbnail of a mirror job under a key derived from the job id, so
	 * re-processing a job (lease expiry, crash before completion) overwrites the
	 * same object and reuses the same file row instead of leaking another one.
	 * Because that key is shared by retries, a failed DB save does not delete the
	 * object: a retry reuses it.
	 */
	public FileAsset storeMirroredThumbnail(long jobId, long ownerUserId, byte[] rawBytes, String contentType,
			String originalFilename) {
		return transcodeAndStore(mirrorStorageKey(jobId), ownerUserId, rawBytes, contentType, originalFilename,
				clock.instant(), false);
	}

	/** The file a previous attempt of this mirror job already stored, if any. */
	public Optional<FileAsset> findMirroredFile(long jobId) {
		return fileAssetRepository.findByStorageKey(mirrorStorageKey(jobId));
	}

	public String resolveSignedUrl(Long fileId) {
		return findSignedUrl(fileId).orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND));
	}

	public Optional<String> findSignedUrl(Long fileId) {
		if (fileId == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(findSignedUrls(List.of(fileId)).get(fileId));
	}

	/**
	 * Signs many files with one DB read and one storage call (no per-file queries
	 * or HTTP calls). Files that do not exist, or that storage could not sign, are
	 * absent from the result. Not transactional: the DB read finishes before the
	 * HTTP call starts.
	 */
	public Map<Long, String> findSignedUrls(Collection<Long> fileIds) {
		if (fileIds == null) {
			return Map.of();
		}
		List<Long> ids = fileIds.stream().filter(Objects::nonNull).distinct().toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<String, Long> fileIdByKey = new LinkedHashMap<>();
		for (FileAsset asset : fileAssetRepository.findAllById(ids)) {
			fileIdByKey.put(asset.getStorageKey(), asset.getId());
		}
		if (fileIdByKey.isEmpty()) {
			return Map.of();
		}
		Map<String, String> urlByKey = objectStorageClient.createSignedUrls(fileIdByKey.keySet(),
				Duration.ofSeconds(properties.signedUrlExpirationSeconds()));
		Map<Long, String> result = new LinkedHashMap<>();
		for (Map.Entry<String, Long> entry : fileIdByKey.entrySet()) {
			String url = urlByKey.get(entry.getKey());
			if (url != null) {
				result.put(entry.getValue(), url);
			}
		}
		return result;
	}

	public boolean isOwnedBy(long fileId, long userId) {
		return fileAssetRepository.existsByIdAndOwnerUserId(fileId, userId);
	}

	/**
	 * Throws {@code FILE_NOT_FOUND} (404) for a missing file and for another user's
	 * file alike.
	 */
	public void requireOwnedFile(long fileId, long userId) {
		if (!isOwnedBy(fileId, userId)) {
			throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
		}
	}

	public void deleteFile(Long fileId) {
		if (fileId == null) {
			throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
		}
		FileAsset asset = fileAssetRepository.findById(fileId)
				.orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND));
		objectStorageClient.deleteObject(asset.getStorageKey());
		fileAssetRepository.delete(asset);
	}

	private FileAsset transcodeAndStore(String storageKey, long ownerUserId, byte[] rawBytes, String contentType,
			String originalFilename, Instant now, boolean deleteObjectIfNotPersisted) {
		ProcessedThumbnail thumbnail = thumbnailProcessor.process(rawBytes, contentType, properties.maxFileSizeBytes());
		String sanitizedName = sanitizeFilename(originalFilename);

		objectStorageClient.putObject(storageKey, thumbnail.bytes(), thumbnail.contentType());
		FileAsset asset = FileAsset.create(storageKey, ownerUserId, sanitizedName, thumbnail.contentType(),
				thumbnail.bytes().length, now);
		boolean persisted = false;
		try {
			FileAsset saved = fileAssetRepository.save(asset);
			persisted = true;
			return saved;
		} finally {
			if (!persisted && deleteObjectIfNotPersisted) {
				objectStorageClient.deleteQuietly(storageKey);
			}
		}
	}

	private String createSignedUrl(String storageKey) {
		return objectStorageClient.createSignedUrl(storageKey,
				Duration.ofSeconds(properties.signedUrlExpirationSeconds()));
	}

	private static String mirrorStorageKey(long jobId) {
		return "thumbnails/mirror/" + jobId + ".webp";
	}

	private static String generateStorageKey(Instant now) {
		return "thumbnails/" + KEY_DATE_FORMAT.format(now) + "/" + UUID.randomUUID() + ".webp";
	}

	private static String sanitizeFilename(String originalFilename) {
		if (originalFilename == null || originalFilename.isBlank()) {
			return "thumbnail.webp";
		}
		String stripped = originalFilename.replace('\\', '/');
		int lastSlash = stripped.lastIndexOf('/');
		String base = lastSlash >= 0 ? stripped.substring(lastSlash + 1) : stripped;
		String trimmed = base.trim();
		if (trimmed.isEmpty()) {
			return "thumbnail.webp";
		}
		return trimmed.length() <= MAX_ORIGINAL_FILENAME_LENGTH
				? trimmed
				: trimmed.substring(0, MAX_ORIGINAL_FILENAME_LENGTH);
	}
}
