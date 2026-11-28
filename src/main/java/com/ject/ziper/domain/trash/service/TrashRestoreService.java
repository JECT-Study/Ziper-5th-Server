package com.ject.ziper.domain.trash.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.trash.dto.RestoreBoardListResponse;
import com.ject.ziper.domain.trash.repository.TrashRestoreRepository;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.persistence.AdvisoryLockRepository;

@Service
public class TrashRestoreService {
	private static final String PRODUCT_CARD_LOCK_PREFIX = "product-card:";

	private final TrashRestoreRepository repository;
	private final AdvisoryLockRepository locks;

	public TrashRestoreService(TrashRestoreRepository repository, AdvisoryLockRepository locks) {
		this.repository = repository;
		this.locks = locks;
	}

	@Transactional(readOnly = true)
	public RestoreBoardListResponse getRestoreBoards(Long userId, Long cardId) {
		if (cardId == null || cardId <= 0L || !repository.existsRestorableCard(userId, cardId)) {
			throw new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND);
		}
		return new RestoreBoardListResponse(repository.findActiveBoards(userId));
	}

	@Transactional
	public void restoreProduct(Long userId, Long cardId, List<Long> targetBoardIds) {
		if (cardId == null || cardId <= 0L) {
			throw new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND);
		}
		if (targetBoardIds == null || targetBoardIds.isEmpty()
				|| targetBoardIds.stream().anyMatch(id -> id == null || id <= 0L)) {
			throw new BusinessException(ErrorCode.INVALID_PRODUCT_CARD_INPUT);
		}
		List<Long> boardIds = new ArrayList<>(new LinkedHashSet<>(targetBoardIds));
		locks.lock("board:" + userId);
		locks.lock(PRODUCT_CARD_LOCK_PREFIX + userId);
		if (!repository.existsRestorableCard(userId, cardId)) {
			throw new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND);
		}
		if (repository.countActiveOwnedBoards(userId, boardIds) != boardIds.size()) {
			throw new BusinessException(ErrorCode.BOARD_NOT_FOUND);
		}
		if (repository.restoreCard(userId, cardId) != 1) {
			throw new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND);
		}
		repository.softDeleteActiveLinks(cardId);
		for (Long boardId : boardIds) {
			repository.reactivateOrCreateLink(boardId, cardId);
		}
	}
}
