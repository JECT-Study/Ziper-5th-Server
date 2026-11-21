package com.ject.ziper.domain.board.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.board.dto.BoardListResponse;
import com.ject.ziper.domain.board.dto.BoardProductListResponse;
import com.ject.ziper.domain.board.service.BoardQueryService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/boards")
@Tag(name = "보드", description = "상품 보드 목록 및 보드 상세 상품 조회 API")
public class BoardQueryController {
	private static final String MAX_LONG_TEXT = String.valueOf(Long.MAX_VALUE);

	private final BoardQueryService boardQueryService;

	public BoardQueryController(BoardQueryService boardQueryService) {
		this.boardQueryService = boardQueryService;
	}

	@GetMapping
	@Operation(summary = "보드 목록 조회", description = "로그인 사용자의 활성 보드 목록을 순수 최신 생성순(createdAt DESC, id DESC)으로 조회합니다. 각 보드의 활성 구매 예정(PLANNED) 상품 개수와 최신 대표 썸네일 최대 3장, 구매 완료 목록 요약(purchasedSummary)을 함께 반환합니다.")
	public ResponseEntity<BoardListResponse> getBoards(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok(boardQueryService.getBoards(extractUserId(jwt)));
	}

	@GetMapping("/{boardId}/products")
	@Operation(summary = "보드 상세 상품 목록 조회", description = "지정한 활성 보드 내 구매 예정(PLANNED) 상품 카드 목록을 4종 정렬(RECENT, OLDEST, PRICE_ASC, PRICE_DESC), 해시태그 필터(tagIds), 커서 페이지네이션(cursor, size)으로 조회합니다.")
	public ResponseEntity<BoardProductListResponse> getBoardProducts(
			@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "조회할 보드 ID", example = "1") @PathVariable Long boardId,
			@Parameter(description = "정렬 기준 (RECENT | OLDEST | PRICE_ASC | PRICE_DESC, 기본값 RECENT)", example = "RECENT") @RequestParam(required = false) String sort,
			@Parameter(description = "해시태그 ID 필터 목록 (지정한 태그 중 하나 이상 포함된 상품만 조회)", example = "[1, 2]") @RequestParam(required = false) List<Long> tagIds,
			@Parameter(description = "다음 페이지 조회용 커서 (직전 응답의 nextCursor 값)", example = "20") @RequestParam(required = false) String cursor,
			@Parameter(description = "페이지 크기 (1~100, 기본값 20)", example = "20") @RequestParam(required = false) Integer size) {
		return ResponseEntity
				.ok(boardQueryService.getBoardProducts(extractUserId(jwt), boardId, sort, tagIds, cursor, size));
	}

	private static Long extractUserId(Jwt jwt) {
		if (jwt == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		String subject = jwt.getSubject();
		if (subject == null || subject.isEmpty() || subject.length() > MAX_LONG_TEXT.length()
				|| !isAsciiDigits(subject)) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		if (subject.length() == MAX_LONG_TEXT.length() && subject.compareTo(MAX_LONG_TEXT) > 0) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		long userId = Long.parseLong(subject);
		if (userId <= 0L) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return userId;
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
}
