package com.ject.ziper.domain.trash.service;

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
	public void permanentlyDeleteProduct(Long userId, Long productId) {
		if (productId == null || productId <= 0L) {
			throw new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND);
		}
		locks.lock("board:" + userId);
		locks.lock(PRODUCT_CARD_LOCK_PREFIX + userId);
		if (!repository.existsOwnedDeletedCard(userId, productId)) {
			throw new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND);
		}
		imageMirrorService.lockActiveJobsForCard(productId);
		if (repository.deleteOwnedDeletedCard(userId, productId) != 1) {
			throw new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND);
		}
	}
}
