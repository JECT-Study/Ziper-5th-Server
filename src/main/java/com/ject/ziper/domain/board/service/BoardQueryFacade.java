package com.ject.ziper.domain.board.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import com.ject.ziper.domain.board.dto.BoardListResponse;
import com.ject.ziper.domain.board.dto.BoardPreviewImageResponse;
import com.ject.ziper.domain.board.dto.BoardProductItemResponse;
import com.ject.ziper.domain.board.dto.BoardProductListResponse;
import com.ject.ziper.domain.board.dto.BoardSummaryResponse;
import com.ject.ziper.domain.board.dto.PurchasedSummaryResponse;
import com.ject.ziper.domain.board.repository.BoardQueryRepository.ActiveBoardRow;
import com.ject.ziper.domain.board.repository.BoardQueryRepository.BoardProductRow;
import com.ject.ziper.domain.board.repository.BoardQueryRepository.PreviewImageRow;
import com.ject.ziper.domain.board.repository.BoardQueryRepository.PurchasedPreviewRow;
import com.ject.ziper.domain.board.service.BoardQueryService.BoardListView;
import com.ject.ziper.domain.board.service.BoardQueryService.BoardProductPage;
import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.domain.product.link.dto.ProductLinkStatusResponse;
import com.ject.ziper.domain.tag.dto.TagResponse;

@Service
public class BoardQueryFacade {
	private final BoardQueryService boardQueryService;
	private final FileImageService fileImageService;

	public BoardQueryFacade(BoardQueryService boardQueryService, FileImageService fileImageService) {
		this.boardQueryService = boardQueryService;
		this.fileImageService = fileImageService;
	}

	public BoardListResponse getBoards(Long userId) {
		BoardListView view = boardQueryService.loadBoards(userId);
		Map<Long, String> signedUrls = sign(Stream.concat(
				view.previewsByBoardId().values().stream().flatMap(List::stream).map(PreviewImageRow::imageFileId),
				view.purchasedRows().stream().map(PurchasedPreviewRow::imageFileId)));

		List<BoardSummaryResponse> boards = view.boards().stream().map(board -> {
			List<BoardPreviewImageResponse> previews = view.previewsByBoardId().getOrDefault(board.boardId(), List.of())
					.stream()
					.map(row -> new BoardPreviewImageResponse(row.cardId(),
							resolvePreviewImageUrl(row.imageFileId(), row.imageUrl(), signedUrls), row.fallbackIcon()))
					.toList();
			return new BoardSummaryResponse(board.boardId(), board.name(), board.modifiable(), board.productCount(),
					previews, board.createdAt());
		}).toList();

		int purchasedCount = view.purchasedRows().isEmpty() ? 0 : view.purchasedRows().getFirst().totalCount();
		List<BoardPreviewImageResponse> purchasedPreviews = view.purchasedRows().stream()
				.map(row -> new BoardPreviewImageResponse(row.cardId(),
						resolvePreviewImageUrl(row.imageFileId(), row.imageUrl(), signedUrls), row.fallbackIcon()))
				.toList();
		PurchasedSummaryResponse purchasedSummary = new PurchasedSummaryResponse(purchasedCount, purchasedPreviews);

		return new BoardListResponse(boards, purchasedSummary);
	}

	public BoardProductListResponse getBoardProducts(Long userId, Long boardId, String rawSort, List<Long> rawTagIds,
			String rawCursor, Integer rawSize) {
		BoardProductPage page = boardQueryService.loadBoardProducts(userId, boardId, rawSort, rawTagIds, rawCursor,
				rawSize);
		Map<Long, String> signedUrls = sign(page.rows().stream().map(BoardProductRow::imageFileId));

		List<BoardProductItemResponse> items = page.rows().stream().map(row -> {
			String imageFileUrl = resolveSignedUrl(row.imageFileId(), signedUrls);
			ProductLinkStatusResponse link = new ProductLinkStatusResponse(row.linkStatus(), row.linkCheckedAt(),
					row.linkCheckSource());
			List<TagResponse> tags = List.copyOf(page.tagsByCardId().getOrDefault(row.cardId(), List.of()));
			return new BoardProductItemResponse(row.cardId(), row.shop(), row.productTitle(), row.price(), row.memo(),
					row.imageUrl(), imageFileUrl, row.fallbackIcon(), row.imageStatus(), link, tags, row.addedAt());
		}).toList();

		ActiveBoardRow board = page.board();
		return new BoardProductListResponse(board.boardId(), board.name(), board.modifiable(), page.productCount(),
				items, page.nextCursor(), page.nextCursor() != null);
	}

	private String resolvePreviewImageUrl(Long imageFileId, String fallbackImageUrl, Map<Long, String> signedUrls) {
		String signedUrl = resolveSignedUrl(imageFileId, signedUrls);
		if (signedUrl != null) {
			return signedUrl;
		}
		return fallbackImageUrl != null && !fallbackImageUrl.isBlank() ? fallbackImageUrl : null;
	}

	private static String resolveSignedUrl(Long imageFileId, Map<Long, String> signedUrls) {
		return imageFileId == null ? null : signedUrls.get(imageFileId);
	}

	private Map<Long, String> sign(Stream<Long> fileIds) {
		return fileImageService.findSignedUrls(fileIds.filter(Objects::nonNull).distinct().toList());
	}
}
