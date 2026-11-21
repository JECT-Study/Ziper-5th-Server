package com.ject.ziper.domain.file.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	@Transactional
	public ImageUploadResponse uploadDirectImage(byte[] rawBytes, String originalFilename, String contentType,
			long declaredSize) {
		if (declaredSize > properties.maxFileSizeBytes()) {
			throw new BusinessException(ErrorCode.IMAGE_FILE_TOO_LARGE);
		}
		if (rawBytes == null || rawBytes.length == 0) {
			throw new BusinessException(ErrorCode.INVALID_IMAGE_FILE);
		}
		FileAsset saved = transcodeAndStore(rawBytes, contentType, originalFilename);
		String signedUrl = createSignedUrl(saved.getStorageKey());
		return new ImageUploadResponse(saved.getId(), signedUrl, saved.getContentType(), saved.getFileSize());
	}

	@Transactional
	public FileAsset storeMirroredThumbnail(byte[] rawBytes, String contentType, String originalFilename) {
		return transcodeAndStore(rawBytes, contentType, originalFilename);
	}

	@Transactional(readOnly = true)
	public String resolveSignedUrl(Long fileId) {
		if (fileId == null) {
			throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
		}
		FileAsset asset = fileAssetRepository.findById(fileId)
				.orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND));
		return createSignedUrl(asset.getStorageKey());
	}

	@Transactional(readOnly = true)
	public Optional<String> findSignedUrl(Long fileId) {
		if (fileId == null) {
			return Optional.empty();
		}
		return fileAssetRepository.findById(fileId).map(asset -> createSignedUrl(asset.getStorageKey()));
	}

	@Transactional(readOnly = true)
	public boolean exists(Long fileId) {
		return fileId != null && fileAssetRepository.existsById(fileId);
	}

	@Transactional
	public void deleteFile(Long fileId) {
		if (fileId == null) {
			throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
		}
		FileAsset asset = fileAssetRepository.findById(fileId)
				.orElseThrow(() -> new BusinessException(ErrorCode.FILE_NOT_FOUND));
		objectStorageClient.deleteObject(asset.getStorageKey());
		fileAssetRepository.delete(asset);
	}

	private FileAsset transcodeAndStore(byte[] rawBytes, String contentType, String originalFilename) {
		ProcessedThumbnail thumbnail = thumbnailProcessor.process(rawBytes, contentType, properties.maxFileSizeBytes());
		Instant now = clock.instant();
		String storageKey = generateStorageKey(now);
		String sanitizedName = sanitizeFilename(originalFilename);

		objectStorageClient.putObject(storageKey, thumbnail.bytes(), thumbnail.contentType());
		FileAsset asset = FileAsset.create(storageKey, sanitizedName, thumbnail.contentType(), thumbnail.bytes().length,
				now);
		return fileAssetRepository.save(asset);
	}

	private String createSignedUrl(String storageKey) {
		return objectStorageClient.createSignedUrl(storageKey,
				Duration.ofSeconds(properties.signedUrlExpirationSeconds()));
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
