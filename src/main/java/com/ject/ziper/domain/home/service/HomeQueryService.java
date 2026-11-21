package com.ject.ziper.domain.home.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.home.repository.HomeQueryRepository;
import com.ject.ziper.domain.home.repository.HomeQueryRepository.BoardPreviewImageRow;
import com.ject.ziper.domain.home.repository.HomeQueryRepository.BoardSummaryRow;
import com.ject.ziper.domain.home.repository.HomeQueryRepository.RecentProductRow;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

@Service
public class HomeQueryService {
	public static final int DEFAULT_RECENT_LIMIT = 10;
	public static final int MIN_RECENT_LIMIT = 1;
	public static final int MAX_RECENT_LIMIT = 50;

	private final HomeQueryRepository homeQueryRepository;
	private final Clock clock;

	@Autowired
	public HomeQueryService(HomeQueryRepository homeQueryRepository) {
		this(homeQueryRepository, Clock.systemUTC());
	}

	public HomeQueryService(HomeQueryRepository homeQueryRepository, Clock clock) {
		this.homeQueryRepository = homeQueryRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public HomeSnapshot loadHomeSnapshot(Long userId, int recentLimit) {
		if (userId == null || userId <= 0L) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		if (recentLimit < MIN_RECENT_LIMIT || recentLimit > MAX_RECENT_LIMIT) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}

		Instant now = clock.instant();
		long rediscoveryCount = homeQueryRepository.countRediscoveryCandidates(userId, now);
		List<BoardSummaryRow> boardRows = homeQueryRepository.findActiveBoardsWithProductCount(userId);
		List<BoardPreviewImageRow> previewRows = boardRows.isEmpty()
				? List.of()
				: homeQueryRepository.findBoardPreviewImages(userId);
		List<RecentProductRow> recentRows = homeQueryRepository.findRecentProducts(userId, recentLimit);

		return new HomeSnapshot(rediscoveryCount, boardRows, previewRows, recentRows);
	}
}
