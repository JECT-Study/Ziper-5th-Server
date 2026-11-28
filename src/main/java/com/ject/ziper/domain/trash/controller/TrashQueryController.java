package com.ject.ziper.domain.trash.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.trash.dto.TrashProductListResponse;
import com.ject.ziper.domain.trash.service.TrashQueryFacade;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/trash")
@Tag(name = "휴지통", description = "삭제된 상품 카드 조회 API")
public class TrashQueryController {
	private static final String MAX_LONG_TEXT = String.valueOf(Long.MAX_VALUE);

	private final TrashQueryFacade queryFacade;

	public TrashQueryController(TrashQueryFacade queryFacade) {
		this.queryFacade = queryFacade;
	}

	@GetMapping("/products")
	@Operation(summary = "휴지통 상품 목록 조회", description = "본인의 삭제된 상품 중 30일 보관 기간이 남은 카드만 최신 삭제순으로 조회합니다. 남은 일수는 올림 계산합니다.")
	public ResponseEntity<TrashProductListResponse> getProducts(
			@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
		return ResponseEntity.ok(queryFacade.getProducts(extractUserId(jwt)));
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
