package com.ject.ziper.domain.board.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.board.dto.CreateBoardResponse;
import com.ject.ziper.domain.board.dto.UpdateBoardResponse;
import com.ject.ziper.domain.board.entity.ProductBoard;
import com.ject.ziper.domain.board.repository.ProductBoardCardCleanupRepository;
import com.ject.ziper.domain.board.repository.ProductBoardRepository;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.persistence.AdvisoryLockRepository;

@Service
public class ProductBoardCommandService {
	private static final int MAX_BOARD_NAME_LENGTH = 10;

	private final ProductBoardRepository productBoardRepository;
	private final ProductBoardCardCleanupRepository cardCleanupRepository;
	private final AdvisoryLockRepository locks;
	private final Clock clock;

	@Autowired
	public ProductBoardCommandService(ProductBoardRepository productBoardRepository,
			ProductBoardCardCleanupRepository cardCleanupRepository, AdvisoryLockRepository locks) {
		this(productBoardRepository, cardCleanupRepository, locks, Clock.systemUTC());
	}

	public ProductBoardCommandService(ProductBoardRepository productBoardRepository,
			ProductBoardCardCleanupRepository cardCleanupRepository, AdvisoryLockRepository locks, Clock clock) {
		this.productBoardRepository = productBoardRepository;
		this.cardCleanupRepository = cardCleanupRepository;
		this.locks = locks;
		this.clock = clock;
	}

	@Transactional
	public CreateBoardResponse createBoard(Long userId, String rawName) {
		String normalizedName = normalizeAndValidateName(rawName);
		locks.lock("board:" + userId);
		if (productBoardRepository.existsByUserIdAndNameAndDeletedFalse(userId, normalizedName)) {
			throw new BusinessException(ErrorCode.DUPLICATE_BOARD_NAME);
		}
		ProductBoard saved = productBoardRepository.save(ProductBoard.createCustom(userId, normalizedName));
		return new CreateBoardResponse(saved.getId(), saved.getName(), saved.isModifiable(), 0, saved.getCreatedAt());
	}

	@Transactional
	public UpdateBoardResponse renameBoard(Long userId, Long boardId, String rawName) {
		locks.lock("board:" + userId);
		ProductBoard board = loadActiveOwnedBoard(userId, boardId);
		verifyModifiable(board);
		String normalizedName = normalizeAndValidateName(rawName);
		if (productBoardRepository.existsByUserIdAndNameAndDeletedFalseAndIdNot(userId, normalizedName, boardId)) {
			throw new BusinessException(ErrorCode.DUPLICATE_BOARD_NAME);
		}
		board.rename(normalizedName);
		return new UpdateBoardResponse(board.getId(), board.getName());
	}

	@Transactional
	public void deleteBoard(Long userId, Long boardId) {
		locks.lock("board:" + userId);
		ProductBoard board = loadActiveOwnedBoard(userId, boardId);
		verifyModifiable(board);
		List<Long> linkedCardIds = cardCleanupRepository.findCardIdsByBoardId(boardId);
		cardCleanupRepository.deleteBoardCardLinks(boardId);
		Instant now = clock.instant();
		board.softDelete(now);
		productBoardRepository.saveAndFlush(board);
		cardCleanupRepository.softDeleteOrphanCards(userId, linkedCardIds, now);
	}

	private ProductBoard loadActiveOwnedBoard(Long userId, Long boardId) {
		return productBoardRepository.findByIdAndUserIdAndDeletedFalse(boardId, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.BOARD_NOT_FOUND));
	}

	private static void verifyModifiable(ProductBoard board) {
		if (!board.isModifiable()) {
			throw new BusinessException(ErrorCode.DEFAULT_BOARD_NOT_MODIFIABLE);
		}
	}

	private static String normalizeAndValidateName(String rawName) {
		if (rawName == null) {
			throw new BusinessException(ErrorCode.INVALID_BOARD_NAME);
		}
		String trimmed = stripInvisible(rawName);
		if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > MAX_BOARD_NAME_LENGTH
				|| containsUnstorableCharacter(trimmed)) {
			throw new BusinessException(ErrorCode.INVALID_BOARD_NAME);
		}
		return trimmed;
	}

	/** 앞뒤의 유니코드 공백과 서식 문자(NBSP, 제로폭 공백 등)를 제거한다. String.strip()은 NBSP를 제거하지 않는다. */
	private static String stripInvisible(String value) {
		int start = 0;
		int end = value.length();
		while (start < end) {
			int codePoint = value.codePointAt(start);
			if (!isStrippable(codePoint)) {
				break;
			}
			start += Character.charCount(codePoint);
		}
		while (end > start) {
			int codePoint = value.codePointBefore(end);
			if (!isStrippable(codePoint)) {
				break;
			}
			end -= Character.charCount(codePoint);
		}
		return value.substring(start, end);
	}

	private static boolean isStrippable(int codePoint) {
		return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
				|| Character.getType(codePoint) == Character.FORMAT;
	}

	/** 제어 문자(NUL 포함)와 짝 없는 서로게이트는 PostgreSQL이 저장하지 못하거나 화면에 안전하지 않다. */
	private static boolean containsUnstorableCharacter(String value) {
		return value.codePoints().anyMatch(codePoint -> {
			int type = Character.getType(codePoint);
			return type == Character.CONTROL || type == Character.SURROGATE;
		});
	}
}
