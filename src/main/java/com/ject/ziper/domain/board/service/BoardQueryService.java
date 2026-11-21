package com.ject.ziper.domain.board.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.board.dto.BoardListResponse;
import com.ject.ziper.domain.board.dto.BoardPreviewImageResponse;
import com.ject.ziper.domain.board.dto.BoardProductItemResponse;
import com.ject.ziper.domain.board.dto.BoardProductListResponse;
import com.ject.ziper.domain.board.dto.BoardProductSort;
import com.ject.ziper.domain.board.dto.BoardSummaryResponse;
import com.ject.ziper.domain.board.dto.PurchasedSummaryResponse;
import com.ject.ziper.domain.board.repository.BoardQueryRepository;
import com.ject.ziper.domain.board.repository.BoardQueryRepository.ActiveBoardRow;
import com.ject.ziper.domain.board.repository.BoardQueryRepository.BoardProductRow;
import com.ject.ziper.domain.board.repository.BoardQueryRepository.BoardSummaryRow;
import com.ject.ziper.domain.board.repository.BoardQueryRepository.PreviewImageRow;
import com.ject.ziper.domain.board.repository.BoardQueryRepository.PurchasedPreviewRow;
import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.domain.product.link.dto.ProductLinkStatusResponse;
import com.ject.ziper.domain.tag.dto.TagResponse;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

@Service
@Transactional(readOnly = true)
public class BoardQueryService {
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MIN_PAGE_SIZE = 1;
	private static final int MAX_PAGE_SIZE = 100;
	private static final String MAX_INT_TEXT = String.valueOf(Integer.MAX_VALUE);

	private final BoardQueryRepository boardQueryRepository;
	private final FileImageService fileImageService;

	public BoardQueryService(BoardQueryRepository boardQueryRepository, FileImageService fileImageService) {
		this.boardQueryRepository = boardQueryRepository;
		this.fileImageService = fileImageService;
	}

	public BoardListResponse getBoards(Long userId) {
		List<BoardSummaryRow> boardRows = boardQueryRepository.findActiveBoardsWithPlannedCounts(userId);
		Map<Long, List<PreviewImageRow>> previewsByBoardId = boardQueryRepository.findBoardPreviewImages(userId);
		List<PurchasedPreviewRow> purchasedRows = boardQueryRepository.findPurchasedSummaryPreviewRows(userId);

		Map<Long, Optional<String>> signedUrlCache = new HashMap<>();

		List<BoardSummaryResponse> boards = boardRows.stream().map(board -> {
			List<BoardPreviewImageResponse> previews = previewsByBoardId.getOrDefault(board.boardId(), List.of())
					.stream()
					.map(row -> new BoardPreviewImageResponse(row.cardId(),
							resolvePreviewImageUrl(row.imageFileId(), row.imageUrl(), signedUrlCache),
							row.fallbackIcon()))
					.toList();
			return new BoardSummaryResponse(board.boardId(), board.name(), board.modifiable(), board.productCount(),
					previews, board.createdAt());
		}).toList();

		int purchasedCount = purchasedRows.isEmpty() ? 0 : purchasedRows.getFirst().totalCount();
		List<BoardPreviewImageResponse> purchasedPreviews = purchasedRows.stream()
				.map(row -> new BoardPreviewImageResponse(row.cardId(),
						resolvePreviewImageUrl(row.imageFileId(), row.imageUrl(), signedUrlCache), row.fallbackIcon()))
				.toList();
		PurchasedSummaryResponse purchasedSummary = new PurchasedSummaryResponse(purchasedCount, purchasedPreviews);

		return new BoardListResponse(boards, purchasedSummary);
	}

	public BoardProductListResponse getBoardProducts(Long userId, Long boardId, String rawSort, List<Long> rawTagIds,
			String rawCursor, Integer rawSize) {
		BoardProductSort sort = resolveSort(rawSort);
		List<Long> tagIds = validateTagIds(rawTagIds);
		int offset = parseCursorOffset(rawCursor);
		int size = validatePageSize(rawSize);

		ActiveBoardRow board = boardQueryRepository.findActiveBoard(userId, boardId)
				.orElseThrow(() -> new BusinessException(ErrorCode.BOARD_NOT_FOUND));

		int productCount = boardQueryRepository.countBoardProducts(userId, boardId, tagIds);
		List<BoardProductRow> fetched = boardQueryRepository.findBoardProducts(userId, boardId, tagIds, sort, offset,
				size + 1);

		boolean hasNext = fetched.size() > size;
		List<BoardProductRow> pageRows = hasNext ? fetched.subList(0, size) : fetched;
		String nextCursor = hasNext ? String.valueOf((long) offset + size) : null;

		List<Long> cardIds = pageRows.stream().map(BoardProductRow::cardId).toList();
		Map<Long, List<TagResponse>> tagsByCardId = boardQueryRepository.findTagsByCardIds(cardIds);
		Map<Long, Optional<String>> signedUrlCache = new HashMap<>();

		List<BoardProductItemResponse> items = pageRows.stream().map(row -> {
			String imageFileUrl = resolveSignedUrl(row.imageFileId(), signedUrlCache);
			ProductLinkStatusResponse link = new ProductLinkStatusResponse(row.linkStatus(), row.linkCheckedAt(),
					row.linkCheckSource());
			List<TagResponse> tags = List.copyOf(tagsByCardId.getOrDefault(row.cardId(), List.of()));
			return new BoardProductItemResponse(row.cardId(), row.shop(), row.productTitle(), row.price(), row.memo(),
					row.imageUrl(), imageFileUrl, row.fallbackIcon(), row.imageStatus(), link, tags, row.addedAt());
		}).toList();

		return new BoardProductListResponse(board.boardId(), board.name(), board.modifiable(), productCount, items,
				nextCursor, hasNext);
	}

	private String resolvePreviewImageUrl(Long imageFileId, String fallbackImageUrl,
			Map<Long, Optional<String>> signedUrlCache) {
		String signedUrl = resolveSignedUrl(imageFileId, signedUrlCache);
		if (signedUrl != null) {
			return signedUrl;
		}
		return fallbackImageUrl != null && !fallbackImageUrl.isBlank() ? fallbackImageUrl : null;
	}

	private String resolveSignedUrl(Long imageFileId, Map<Long, Optional<String>> signedUrlCache) {
		if (imageFileId == null) {
			return null;
		}
		return signedUrlCache.computeIfAbsent(imageFileId, fileImageService::findSignedUrl).orElse(null);
	}

	private static BoardProductSort resolveSort(String rawSort) {
		if (rawSort == null) {
			return BoardProductSort.RECENT;
		}
		return BoardProductSort.fromValue(rawSort).orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST));
	}

	private static List<Long> validateTagIds(List<Long> rawTagIds) {
		if (rawTagIds == null || rawTagIds.isEmpty()) {
			return List.of();
		}
		for (Long tagId : rawTagIds) {
			if (tagId == null || tagId <= 0L) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST);
			}
		}
		return rawTagIds.stream().distinct().toList();
	}

	private static int parseCursorOffset(String rawCursor) {
		if (rawCursor == null || rawCursor.isEmpty()) {
			return 0;
		}
		if (rawCursor.length() > MAX_INT_TEXT.length() || !isAsciiDigits(rawCursor)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		if (rawCursor.length() == MAX_INT_TEXT.length() && rawCursor.compareTo(MAX_INT_TEXT) > 0) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return Integer.parseInt(rawCursor);
	}

	private static boolean isAsciiDigits(String value) {
		for (int i = 0; i < value.length(); i++) {
			char ch = value.charAt(i);
			if (ch < '0' || ch > '9') {
				return false;
			}
		}
		return true;
	}

	private static int validatePageSize(Integer rawSize) {
		if (rawSize == null) {
			return DEFAULT_PAGE_SIZE;
		}
		if (rawSize < MIN_PAGE_SIZE || rawSize > MAX_PAGE_SIZE) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return rawSize;
	}
}
