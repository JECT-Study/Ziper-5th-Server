package com.ject.ziper.domain.product.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.product.dto.PurchaseProductResponse;
import com.ject.ziper.domain.product.dto.PurchasedProductListResponse;
import com.ject.ziper.domain.product.service.PurchasedProductFacade;
import com.ject.ziper.domain.product.service.PurchasedProductService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "구매 완료", description = "상품 구매 완료 전환 및 구매 완료 목록 API")
public class PurchasedProductController {
	private static final String MAX_LONG_TEXT = String.valueOf(Long.MAX_VALUE);

	private final PurchasedProductService service;
	private final PurchasedProductFacade facade;

	public PurchasedProductController(PurchasedProductService service, PurchasedProductFacade facade) {
		this.service = service;
		this.facade = facade;
	}

	@PostMapping("/{cardId}/purchase")
	@Operation(summary = "상품 구매 완료 전환", description = "본인의 활성 상품 카드 한 건을 구매 완료로 바꿉니다. 보드 연결은 유지하고 모든 일반 보드 및 재발견 대상에서 제외합니다.")
	public ResponseEntity<PurchaseProductResponse> purchase(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "구매 완료할 카드 ID") @PathVariable Long cardId) {
		return ResponseEntity.ok(service.purchase(extractUserId(jwt), cardId));
	}

	@GetMapping("/purchased")
	@Operation(summary = "구매 완료 상품 목록", description = "보드별 중복 없이 상품 카드마다 한 번만 반환합니다. 기본은 최초 등록 최신순이며 4종 정렬과 커서 페이지네이션을 지원합니다.")
	public ResponseEntity<PurchasedProductListResponse> getPurchased(
			@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "RECENT | OLDEST | PRICE_ASC | PRICE_DESC (기본 RECENT)") @RequestParam(required = false) String sort,
			@Parameter(description = "직전 응답의 nextCursor") @RequestParam(required = false) String cursor,
			@Parameter(description = "페이지 크기 1~100 (기본 20)") @RequestParam(required = false) Integer size) {
		return ResponseEntity.ok(facade.getPurchased(extractUserId(jwt), sort, cursor, size));
	}

	private static Long extractUserId(Jwt jwt) {
		if (jwt == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		String subject = jwt.getSubject();
		if (subject == null || subject.isEmpty() || subject.length() > MAX_LONG_TEXT.length()
				|| !subject.chars().allMatch(Character::isDigit)
				|| (subject.length() == MAX_LONG_TEXT.length() && subject.compareTo(MAX_LONG_TEXT) > 0)) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		long userId = Long.parseLong(subject);
		if (userId <= 0L) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return userId;
	}
}
