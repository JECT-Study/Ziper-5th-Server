package com.ject.ziper.domain.file.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "files", schema = "ziper")
public class FileAsset {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "storage_key", nullable = false, unique = true, length = 512)
	private String storageKey;

	@Column(name = "owner_user_id")
	private Long ownerUserId;

	@Column(name = "original_filename", nullable = false, length = 255)
	private String originalFilename;

	@Column(name = "content_type", nullable = false, length = 128)
	private String contentType;

	@Column(name = "file_size", nullable = false)
	private long fileSize;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected FileAsset() {
	}

	private FileAsset(String storageKey, Long ownerUserId, String originalFilename, String contentType, long fileSize,
			Instant createdAt) {
		this.storageKey = storageKey;
		this.ownerUserId = ownerUserId;
		this.originalFilename = originalFilename;
		this.contentType = contentType;
		this.fileSize = fileSize;
		this.createdAt = createdAt;
	}

	public static FileAsset create(String storageKey, Long ownerUserId, String originalFilename, String contentType,
			long fileSize, Instant createdAt) {
		return new FileAsset(storageKey, ownerUserId, originalFilename, contentType, fileSize, createdAt);
	}

	public Long getId() {
		return id;
	}

	public Long getOwnerUserId() {
		return ownerUserId;
	}

	public String getStorageKey() {
		return storageKey;
	}

	public String getOriginalFilename() {
		return originalFilename;
	}

	public String getContentType() {
		return contentType;
	}

	public long getFileSize() {
		return fileSize;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
