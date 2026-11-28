package com.ject.ziper.domain.trash.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.file.service.ImageMirrorService;
import com.ject.ziper.domain.trash.repository.TrashProductCommandRepository;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.persistence.AdvisoryLockRepository;

@Service
public class TrashProductCommandService {
	private static final String PRODUCT_CARD_LOCK_PREFIX = "product-card:";

	private final TrashProductCommandRepository repository;
	private final AdvisoryLockRepository locks;
	private final ImageMirrorService imageMirrorService;

	public TrashProductCommandService(TrashProductCommandRepository repository, AdvisoryLockRepository locks,
			ImageMirrorService imageMirrorService) {
		this.repository = repository;
		this.locks = locks;
		this.imageMirrorService = imageMirrorService;
	}

	@Transactional
	public void permanentlyDeleteProducts(Long userId, List<Long> productIds) {
		if (productIds == null || productIds.isEmpty() || productIds.stream().anyMatch(id -> id == null || id <= 0L)) {
			throw new BusinessException(ErrorCode.INVALID_PRODUCT_CARD_INPUT);
		}
		List<Long> distinctIds = new ArrayList<>(new LinkedHashSet<>(productIds));
		locks.lock("board:" + userId);
		locks.lock(PRODUCT_CARD_LOCK_PREFIX + userId);
		for (Long id : distinctIds) {
			if (!repository.existsOwnedDeletedCard(userId, id)) {
				throw new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND);
			}
		}
		for (Long id : distinctIds) {
			imageMirrorService.lockActiveJobsForCard(id);
			if (repository.deleteOwnedDeletedCard(userId, id) != 1) {
				throw new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND);
			}
		}
	}
}
