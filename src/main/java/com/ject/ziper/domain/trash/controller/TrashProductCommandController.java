package com.ject.ziper.domain.trash.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.trash.dto.PermanentlyDeleteTrashProductsRequest;
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

	@DeleteMapping
	@Operation(summary = "휴지통 상품 즉시 영구 삭제", description = "productIds에 카드 ID를 1개 또는 여러 개 지정합니다. 선택한 본인 휴지통 상품을 한 트랜잭션에서 삭제하며, 하나라도 삭제할 수 없으면 전체 요청을 거절합니다.")
	@ApiResponse(responseCode = "204", description = "선택한 상품 영구 삭제 완료")
	public ResponseEntity<Void> permanentlyDeleteProducts(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
			@RequestBody PermanentlyDeleteTrashProductsRequest request) {
		commandService.permanentlyDeleteProducts(extractUserId(jwt), request == null ? null : request.productIds());
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
