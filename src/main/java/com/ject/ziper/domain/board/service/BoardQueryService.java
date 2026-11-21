package com.ject.ziper.domain.board.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.board.dto.BoardProductCursor;
import com.ject.ziper.domain.board.dto.BoardProductSort;
import com.ject.ziper.domain.board.repository.BoardQueryRepository;
import com.ject.ziper.domain.board.repository.BoardQueryRepository.ActiveBoardRow;
import com.ject.ziper.domain.board.repository.BoardQueryRepository.BoardProductRow;
import com.ject.ziper.domain.board.repository.BoardQueryRepository.BoardSummaryRow;
import com.ject.ziper.domain.board.repository.BoardQueryRepository.PreviewImageRow;
import com.ject.ziper.domain.board.repository.BoardQueryRepository.PurchasedPreviewRow;
import com.ject.ziper.domain.tag.dto.TagResponse;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

@Service
@Transactional(readOnly = true)
public class BoardQueryService {
	private static final int DEFAULT_PAGE_SIZE = 20;
	private static final int MAX_PAGE_SIZE = 100;

	private final BoardQueryRepository boardQueryRepository;

	public BoardQueryService(BoardQueryRepository boardQueryRepository) {
		this.boardQueryRepository = boardQueryRepository;
	}

	public BoardListView loadBoards(Long userId) {
		return new BoardListView(boardQueryRepository.findActiveBoardsWithPlannedCounts(userId),
				boardQueryRepository.findBoardPreviewImages(userId),
				boardQueryRepository.findPurchasedSummaryPreviewRows(userId));
	}

	public BoardProductPage loadBoardProducts(Long userId, Long boardId, String rawSort, List<Long> rawTagIds,
			String rawCursor, Integer rawSize) {
		BoardProductSort sort = resolveSort(rawSort);
		List<Long> tagIds = validateTagIds(rawTagIds);
		BoardProductCursor cursor = BoardProductCursor.parse(rawCursor, boardId, sort);
		int size = validatePageSize(rawSize);

		ActiveBoardRow board = boardQueryRepository.findActiveBoard(userId, boardId)
				.orElseThrow(() -> new BusinessException(ErrorCode.BOARD_NOT_FOUND));

		int productCount = boardQueryRepository.countBoardProducts(userId, boardId, tagIds);
		List<BoardProductRow> rows = boardQueryRepository.findBoardProducts(userId, boardId, tagIds, sort, cursor,
				size + 1);
		String nextCursor = null;
		if (rows.size() > size) {
			rows = rows.subList(0, size);
			BoardProductRow last = rows.getLast();
			nextCursor = new BoardProductCursor(last.addedAt(), last.boardCardId(), last.price()).encode(boardId, sort);
		}

		List<Long> cardIds = rows.stream().map(BoardProductRow::cardId).toList();
		return new BoardProductPage(board, productCount, rows, boardQueryRepository.findTagsByCardIds(cardIds),
				nextCursor);
	}

	record BoardListView(List<BoardSummaryRow> boards, Map<Long, List<PreviewImageRow>> previewsByBoardId,
			List<PurchasedPreviewRow> purchasedRows) {
	}

	record BoardProductPage(ActiveBoardRow board, int productCount, List<BoardProductRow> rows,
			Map<Long, List<TagResponse>> tagsByCardId, String nextCursor) {
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

	private static int validatePageSize(Integer rawSize) {
		if (rawSize == null) {
			return DEFAULT_PAGE_SIZE;
		}
		if (rawSize < 1 || rawSize > MAX_PAGE_SIZE) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return rawSize;
	}
}
