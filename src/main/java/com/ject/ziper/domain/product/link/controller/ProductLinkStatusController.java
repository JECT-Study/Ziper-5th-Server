package com.ject.ziper.domain.product.link.controller;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.product.link.dto.ProductLinkReportRequest;
import com.ject.ziper.domain.product.link.dto.ProductLinkStatusResponse;
import com.ject.ziper.domain.product.link.service.ProductLinkService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "상품 링크 상태", description = "상품 카드의 외부 링크 판매·품절·삭제 상태 조회 및 클라이언트 관측 결과 보고 API")
public class ProductLinkStatusController {
	private final ProductLinkService productLinkService;

	public ProductLinkStatusController(ProductLinkService productLinkService) {
		this.productLinkService = productLinkService;
	}

	@GetMapping("/{cardId}/link-status")
	@Operation(summary = "상품 카드 링크 상태 조회", description = "본인이 소유한 활성 상품 카드의 현재 링크 상태, 마지막 확인 시각, 확인 출처를 조회합니다.")
	public ResponseEntity<ProductLinkStatusResponse> getLinkStatus(
			@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "상품 카드 ID", example = "1") @PathVariable Long cardId) {
		Long userId = extractUserId(jwt);
		return ResponseEntity.ok(productLinkService.getLinkStatus(userId, cardId));
	}

	@PostMapping("/{cardId}/link-status/reports")
	@Operation(summary = "클라이언트 상품 링크 상태 보고", description = "클라이언트가 인앱 브라우저 등에서 관측한 링크 상태(AVAILABLE, SOLD_OUT, REMOVED)를 보고합니다. 본인 카드에는 즉시 반영되며 24시간 내 서로 다른 2명 이상이 동일 상태를 보고하면 공유 링크와 다른 카드에도 전파됩니다.")
	public ResponseEntity<ProductLinkStatusResponse> reportLinkStatus(
			@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "상품 카드 ID", example = "1") @PathVariable Long cardId,
			@Valid @RequestBody ProductLinkReportRequest request) {
		Long userId = extractUserId(jwt);
		return ResponseEntity
				.ok(productLinkService.reportLinkStatus(userId, cardId, request.status(), request.observedAt()));
	}

	private static Long extractUserId(Jwt jwt) {
		if (jwt == null || jwt.getSubject() == null || !jwt.getSubject().matches("\\d{1,18}")) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return Long.valueOf(jwt.getSubject());
	}
}
