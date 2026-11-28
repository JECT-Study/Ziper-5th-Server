package com.ject.ziper.domain.trash.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.trash.service.TrashProductCommandService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/trash/products")
@Tag(name = "휴지통", description = "삭제된 상품 카드 영구 삭제 API")
public class TrashProductCommandController {
	private static final String MAX_LONG_TEXT = String.valueOf(Long.MAX_VALUE);

	private final TrashProductCommandService commandService;

	public TrashProductCommandController(TrashProductCommandService commandService) {
		this.commandService = commandService;
	}

	@DeleteMapping("/{productId}")
	@Operation(summary = "휴지통 상품 즉시 영구 삭제", description = "본인의 휴지통 상품 카드를 DB에서 즉시 영구 삭제합니다. 연결된 보드·태그·이벤트·이미지 작업도 함께 삭제됩니다.")
	@ApiResponse(responseCode = "204", description = "영구 삭제 완료")
	public ResponseEntity<Void> permanentlyDeleteProduct(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "영구 삭제할 상품 카드 ID", example = "10") @PathVariable Long productId) {
		commandService.permanentlyDeleteProduct(extractUserId(jwt), productId);
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
