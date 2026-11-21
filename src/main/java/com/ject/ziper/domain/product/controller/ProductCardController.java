package com.ject.ziper.domain.product.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.product.dto.CreateProductCardRequest;
import com.ject.ziper.domain.product.dto.CreateProductCardResponse;
import com.ject.ziper.domain.product.service.ProductCardRegistrationService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "상품 카드", description = "상품 카드 신규 등록 API")
public class ProductCardController {
	private static final String MAX_LONG_TEXT = String.valueOf(Long.MAX_VALUE);

	private final ProductCardRegistrationService registrationService;

	public ProductCardController(ProductCardRegistrationService registrationService) {
		this.registrationService = registrationService;
	}

	@PostMapping
	@Operation(summary = "상품 카드 신규 등록", description = "클라이언트 추출(CLIENT) 또는 수기 입력(MANUAL) 상품 정보를 검증·정규화하여 새 상품 카드를 등록합니다. 선택한 보드 및 해시태그 연결, 공유 링크 상태 연동, 외부 OG 이미지 비동기 미러링 작업 등록을 단일 트랜잭션으로 처리합니다.")
	public ResponseEntity<CreateProductCardResponse> createProductCard(
			@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt, @RequestBody CreateProductCardRequest request) {
		CreateProductCardResponse response = registrationService.registerProductCard(extractUserId(jwt), request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
