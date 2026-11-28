package com.ject.ziper.domain.product.service;

import java.time.Clock;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.product.dto.PurchaseProductResponse;
import com.ject.ziper.domain.product.dto.PurchasedProductCursor;
import com.ject.ziper.domain.product.dto.PurchasedProductSort;
import com.ject.ziper.domain.product.entity.ProductCard;
import com.ject.ziper.domain.product.entity.ProductCardStatus;
import com.ject.ziper.domain.product.repository.ProductCardRepository;
import com.ject.ziper.domain.product.repository.PurchasedProductRepository;
import com.ject.ziper.domain.product.repository.PurchasedProductRepository.PurchasedRow;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.persistence.AdvisoryLockRepository;

@Service
public class PurchasedProductService {
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 100;

	private final ProductCardRepository cards;
	private final PurchasedProductRepository purchased;
	private final AdvisoryLockRepository locks;
	private final Clock clock;

	@Autowired
	public PurchasedProductService(ProductCardRepository cards, PurchasedProductRepository purchased,
			AdvisoryLockRepository locks) {
		this(cards, purchased, locks, Clock.systemUTC());
	}

	public PurchasedProductService(ProductCardRepository cards, PurchasedProductRepository purchased,
			AdvisoryLockRepository locks, Clock clock) {
		this.cards = cards;
		this.purchased = purchased;
		this.locks = locks;
		this.clock = clock;
	}

	@Transactional
	public PurchaseProductResponse purchase(Long userId, Long cardId) {
		if (cardId == null || cardId <= 0L) {
			throw new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND);
		}
		locks.lock(ProductCardRegistrationService.PRODUCT_CARD_LOCK_PREFIX + userId);
		ProductCard card = cards.findLockedByIdAndUserIdAndDeletedFalse(cardId, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND));
		if (card.getStatus() != ProductCardStatus.PURCHASED) {
			card.markPurchased(clock.instant());
			cards.saveAndFlush(card);
		}
		return new PurchaseProductResponse(cardId, ProductCardStatus.PURCHASED,
				purchased.countActiveBoards(userId, cardId));
	}

	@Transactional(readOnly = true)
	public PurchasedPage loadPurchased(Long userId, String rawSort, String rawCursor, Integer rawSize) {
		PurchasedProductSort sort = resolveSort(rawSort);
		PurchasedProductCursor cursor = PurchasedProductCursor.parse(rawCursor, sort);
		int size = resolveSize(rawSize);
		int count = purchased.countPurchased(userId);
		List<PurchasedRow> rows = purchased.findPurchased(userId, sort, cursor, size + 1);
		String nextCursor = null;
		if (rows.size() > size) {
			rows = rows.subList(0, size);
			PurchasedRow last = rows.getLast();
			nextCursor = new PurchasedProductCursor(last.createdAt(), last.cardId(), last.price()).encode(sort);
		}
		return new PurchasedPage(count, rows, nextCursor);
	}

	public record PurchasedPage(int totalCount, List<PurchasedRow> rows, String nextCursor) {
	}

	private static PurchasedProductSort resolveSort(String rawSort) {
		if (rawSort == null) {
			return PurchasedProductSort.RECENT;
		}
		return PurchasedProductSort.fromValue(rawSort)
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
	}

	private static int resolveSize(Integer rawSize) {
		if (rawSize == null) {
			return DEFAULT_PAGE_SIZE;
		}
		if (rawSize < 1 || rawSize > MAX_PAGE_SIZE) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return rawSize;
	}
}
