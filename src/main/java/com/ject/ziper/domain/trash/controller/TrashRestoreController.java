package com.ject.ziper.domain.trash.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.trash.dto.RestoreBoardListResponse;
import com.ject.ziper.domain.trash.dto.RestoreTrashProductRequest;
import com.ject.ziper.domain.trash.service.TrashRestoreService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/trash/products/{productId}")
@Tag(name = "휴지통", description = "삭제된 상품 카드의 보드 선택 복구 API")
public class TrashRestoreController {
	private static final String MAX_LONG_TEXT = String.valueOf(Long.MAX_VALUE);

	private final TrashRestoreService restoreService;

	public TrashRestoreController(TrashRestoreService restoreService) {
		this.restoreService = restoreService;
	}

	@GetMapping("/restore-boards")
	@Operation(summary = "휴지통 상품 복구 보드 목록", description = "삭제 후 30일이 지나지 않은 본인 상품에 대해 현재 소유한 활성 보드를 반환합니다.")
	public ResponseEntity<RestoreBoardListResponse> getRestoreBoards(
			@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "휴지통 상품 카드 ID", example = "10") @PathVariable Long productId) {
		return ResponseEntity.ok(restoreService.getRestoreBoards(extractUserId(jwt), productId));
	}

	@PostMapping("/restore")
	@Operation(summary = "휴지통 상품 지정 보드 복구", description = "현재 소유한 활성 보드 1개 이상을 새로 선택하여 상품을 연결하고 최초 14일 재발견 주기로 초기화합니다.")
	@ApiResponse(responseCode = "204", description = "복구 완료")
	public ResponseEntity<Void> restoreProduct(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "휴지통 상품 카드 ID", example = "10") @PathVariable Long productId,
			@RequestBody RestoreTrashProductRequest request) {
		restoreService.restoreProduct(extractUserId(jwt), productId, request == null ? null : request.targetBoardIds());
		return ResponseEntity.noContent().build();
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
