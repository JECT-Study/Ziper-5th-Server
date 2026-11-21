package com.ject.ziper.domain.home.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.domain.home.dto.HomeBoardSummaryResponse;
import com.ject.ziper.domain.home.dto.HomePreviewImageResponse;
import com.ject.ziper.domain.home.dto.HomeRecentProductResponse;
import com.ject.ziper.domain.home.dto.HomeSummaryResponse;
import com.ject.ziper.domain.home.repository.HomeQueryRepository;
import com.ject.ziper.domain.home.repository.HomeQueryRepository.BoardPreviewImageRow;
import com.ject.ziper.domain.home.repository.HomeQueryRepository.BoardSummaryRow;
import com.ject.ziper.domain.home.repository.HomeQueryRepository.RecentProductRow;
import com.ject.ziper.domain.product.link.dto.ProductLinkStatusResponse;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

@Service
public class HomeQueryService {
	public static final int DEFAULT_RECENT_LIMIT = 10;
	public static final int MIN_RECENT_LIMIT = 1;
	public static final int MAX_RECENT_LIMIT = 50;

	private final HomeQueryRepository homeQueryRepository;
	private final FileImageService fileImageService;
	private final Clock clock;

	@Autowired
	public HomeQueryService(HomeQueryRepository homeQueryRepository, FileImageService fileImageService) {
		this(homeQueryRepository, fileImageService, Clock.systemUTC());
	}

	public HomeQueryService(HomeQueryRepository homeQueryRepository, FileImageService fileImageService, Clock clock) {
		this.homeQueryRepository = homeQueryRepository;
		this.fileImageService = fileImageService;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public HomeSummaryResponse getHomeSummary(Long userId, int recentLimit) {
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

		Map<Long, Optional<String>> signedUrlCache = new HashMap<>();
		Map<Long, List<HomePreviewImageResponse>> previewsByBoardId = new HashMap<>();
		for (BoardPreviewImageRow previewRow : previewRows) {
			String signedUrl = resolveSignedUrl(previewRow.imageFileId(), signedUrlCache);
			String resolvedImageUrl = signedUrl != null ? signedUrl : previewRow.imageUrl();
			previewsByBoardId.computeIfAbsent(previewRow.boardId(), key -> new ArrayList<>()).add(
					new HomePreviewImageResponse(previewRow.cardId(), resolvedImageUrl, previewRow.fallbackIcon()));
		}

		List<HomeBoardSummaryResponse> boards = boardRows.stream()
				.map(board -> new HomeBoardSummaryResponse(board.boardId(), board.name(), board.modifiable(),
						board.productCount(), List.copyOf(previewsByBoardId.getOrDefault(board.boardId(), List.of())),
						board.createdAt()))
				.toList();

		List<HomeRecentProductResponse> recentProducts = recentRows.stream().map(row -> {
			String imageFileUrl = resolveSignedUrl(row.imageFileId(), signedUrlCache);
			ProductLinkStatusResponse link = new ProductLinkStatusResponse(row.linkStatus(), row.linkCheckedAt(),
					row.linkCheckSource());
			return new HomeRecentProductResponse(row.cardId(), row.shop(), row.productTitle(), row.price(),
					row.imageUrl(), imageFileUrl, row.fallbackIcon(), row.imageStatus(), link, row.createdAt());
		}).toList();

		return new HomeSummaryResponse(rediscoveryCount, boards, recentProducts);
	}

	private String resolveSignedUrl(Long imageFileId, Map<Long, Optional<String>> signedUrlCache) {
		if (imageFileId == null) {
			return null;
		}
		return signedUrlCache.computeIfAbsent(imageFileId, fileImageService::findSignedUrl).orElse(null);
	}
}
