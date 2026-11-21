package com.ject.ziper.domain.home.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.domain.home.dto.HomeBoardSummaryResponse;
import com.ject.ziper.domain.home.dto.HomePreviewImageResponse;
import com.ject.ziper.domain.home.dto.HomeRecentProductResponse;
import com.ject.ziper.domain.home.dto.HomeSummaryResponse;
import com.ject.ziper.domain.home.repository.HomeQueryRepository.BoardPreviewImageRow;
import com.ject.ziper.domain.product.link.dto.ProductLinkStatusResponse;

/**
 * 읽기 트랜잭션({@link HomeQueryService})이 끝난 뒤에 서명 URL을 조립한다. 화면에 필요한 고유 파일 ID를 모아
 * {@link FileImageService#findSignedUrls}를 한 번만 호출하므로 외부 HTTP 호출 동안 DB 연결을 잡지
 * 않고 카드·보드 수와 무관하게 서명 호출은 1회다. 일괄 응답에서 빠진 파일은 ADR 0017 표시
 * 우선순위({@code imageFileUrl ?? imageUrl}, 이후 {@code fallbackIcon})대로 원본 값으로
 * 폴백한다. 서명 호출 전체가 실패하면 예외를 그대로 전파한다. 이 클래스는 {@code @Transactional}을 붙이지 않는다.
 */
@Service
public class HomeQueryFacade {
	private final HomeQueryService homeQueryService;
	private final FileImageService fileImageService;

	public HomeQueryFacade(HomeQueryService homeQueryService, FileImageService fileImageService) {
		this.homeQueryService = homeQueryService;
		this.fileImageService = fileImageService;
	}

	public HomeSummaryResponse getHomeSummary(Long userId, int recentLimit) {
		HomeSnapshot snapshot = homeQueryService.loadHomeSnapshot(userId, recentLimit);

		Set<Long> fileIds = new LinkedHashSet<>();
		snapshot.previewRows().forEach(row -> addIfPresent(fileIds, row.imageFileId()));
		snapshot.recentRows().forEach(row -> addIfPresent(fileIds, row.imageFileId()));
		Map<Long, String> signedUrls = fileIds.isEmpty() ? Map.of() : fileImageService.findSignedUrls(fileIds);

		Map<Long, List<HomePreviewImageResponse>> previewsByBoardId = new HashMap<>();
		for (BoardPreviewImageRow previewRow : snapshot.previewRows()) {
			String signedUrl = lookup(signedUrls, previewRow.imageFileId());
			String resolvedImageUrl = signedUrl != null ? signedUrl : previewRow.imageUrl();
			previewsByBoardId.computeIfAbsent(previewRow.boardId(), key -> new ArrayList<>()).add(
					new HomePreviewImageResponse(previewRow.cardId(), resolvedImageUrl, previewRow.fallbackIcon()));
		}

		List<HomeBoardSummaryResponse> boards = snapshot.boardRows().stream()
				.map(board -> new HomeBoardSummaryResponse(board.boardId(), board.name(), board.modifiable(),
						board.productCount(), List.copyOf(previewsByBoardId.getOrDefault(board.boardId(), List.of())),
						board.createdAt()))
				.toList();

		List<HomeRecentProductResponse> recentProducts = snapshot.recentRows().stream().map(row -> {
			String imageFileUrl = lookup(signedUrls, row.imageFileId());
			ProductLinkStatusResponse link = new ProductLinkStatusResponse(row.linkStatus(), row.linkCheckedAt(),
					row.linkCheckSource());
			return new HomeRecentProductResponse(row.cardId(), row.shop(), row.productTitle(), row.price(),
					row.imageUrl(), imageFileUrl, row.fallbackIcon(), row.imageStatus(), link, row.createdAt());
		}).toList();

		return new HomeSummaryResponse(snapshot.rediscoveryCount(), boards, recentProducts);
	}

	private static void addIfPresent(Set<Long> fileIds, Long fileId) {
		if (fileId != null) {
			fileIds.add(fileId);
		}
	}

	private static String lookup(Map<Long, String> signedUrls, Long fileId) {
		return fileId == null ? null : signedUrls.get(fileId);
	}
}
