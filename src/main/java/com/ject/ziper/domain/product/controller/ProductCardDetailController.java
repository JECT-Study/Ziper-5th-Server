package com.ject.ziper.domain.product.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.product.dto.OpenOriginalResponse;
import com.ject.ziper.domain.product.dto.ProductCardDetailResponse;
import com.ject.ziper.domain.product.dto.UpdateProductCardRequest;
import com.ject.ziper.domain.product.service.ProductCardDetailService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "상품 카드 상세", description = "상품 카드 상세 조회·수정·전체 삭제·원본 쇼핑몰 이동 이벤트 API")
public class ProductCardDetailController {
	private static final String MAX_LONG_TEXT = String.valueOf(Long.MAX_VALUE);

	private final ProductCardDetailService detailService;

	public ProductCardDetailController(ProductCardDetailService detailService) {
		this.detailService = detailService;
	}

	@GetMapping("/{cardId}")
	@Operation(summary = "상품 카드 상세 조회", description = "본인이 소유한 활성 상품 카드의 전체 상세 정보(상품명·가격·메모·이미지·링크 상태·연결 보드·해시태그)를 조회합니다. 조회 시 재발견 주기가 0단계(+14일)로 초기화되고 VIEW_DETAIL 이벤트가 기록됩니다. 링크가 REMOVED 또는 SOLD_OUT 상태여도 저장된 정보를 그대로 조회할 수 있습니다.")
	public ResponseEntity<ProductCardDetailResponse> getProductCardDetail(
			@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "조회할 상품 카드 ID", example = "1") @PathVariable Long cardId) {
		ProductCardDetailResponse response = detailService.getProductCardDetail(extractUserId(jwt), cardId);
		return ResponseEntity.ok(response);
	}

	@PostMapping("/{cardId}/open-original")
	@Operation(summary = "원본 쇼핑몰 이동 이벤트 기록", description = "사용자가 원본 쇼핑몰 링크를 열어 이동했음을 기록합니다. 재발견 주기가 0단계(+14일)로 초기화되고 OPEN_ORIGINAL_URL 이벤트가 저장되며, 상품 카드의 구매 상태(PLANNED/PURCHASED)는 변경되지 않습니다.")
	public ResponseEntity<OpenOriginalResponse> openOriginalUrl(
			@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "이동할 상품 카드 ID", example = "1") @PathVariable Long cardId) {
		OpenOriginalResponse response = detailService.openOriginalUrl(extractUserId(jwt), cardId);
		return ResponseEntity.ok(response);
	}

	@PatchMapping("/{cardId}")
	@Operation(summary = "상품 카드 정보 수정", description = "본인이 소유한 활성 상품 카드의 상품명·쇼핑몰·가격·메모·이미지·대체 아이콘·해시태그·연결 보드를 수정합니다. 기존에 유지되는 보드 연결의 생성 시각은 보존되며, 외부 이미지 URL 변경 등으로 미러링이 필요하면 비동기 이미지 미러링 작업을 재등록합니다.")
	public ResponseEntity<ProductCardDetailResponse> updateProductCard(
			@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "수정할 상품 카드 ID", example = "1") @PathVariable Long cardId,
			@RequestBody UpdateProductCardRequest request) {
		ProductCardDetailResponse response = detailService.updateProductCard(extractUserId(jwt), cardId, request);
		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/{cardId}")
	@Operation(summary = "상품 카드 전체 삭제", description = "본인이 소유한 활성 상품 카드를 소프트 삭제하여 30일 휴지통으로 이동합니다. 휴지통 복원 시 기존 보드 연결 복구(previousBoardIds)를 위해 보드-상품 연결 레코드는 유지됩니다.")
	public ResponseEntity<Void> deleteProductCard(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "삭제할 상품 카드 ID", example = "1") @PathVariable Long cardId) {
		detailService.deleteProductCard(extractUserId(jwt), cardId);
		return ResponseEntity.noContent().build();
	}

	private static Long extractUserId(Jwt jwt) {
		if (jwt == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		String subject = jwt.getSubject();
		if (subject == null || subject.isEmpty() || subject.length() > MAX_LONG_TEXT.length()
				|| !subject.chars().allMatch(Character::isDigit)) {
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
}
