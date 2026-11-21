package com.ject.ziper.domain.file.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ject.ziper.domain.file.entity.FileAsset;

public interface FileAssetRepository extends JpaRepository<FileAsset, Long> {
	Optional<FileAsset> findByStorageKey(String storageKey);

	boolean existsByIdAndOwnerUserId(Long id, Long ownerUserId);
}
