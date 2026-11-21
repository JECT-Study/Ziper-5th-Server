package com.ject.ziper.domain.product.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "상품 URL 확인 및 정규화 응답")
public record ProductUrlCheckResponse(
		@Schema(description = "정규화된 표준 상품 URL", example = "https://www.musinsa.com/products/1234567", requiredMode = RequiredMode.REQUIRED) String canonicalUrl,
		@Schema(description = "판정된 1차 지원 쇼핑몰명 (미지원 쇼핑몰이면 null)", example = "무신사", requiredMode = RequiredMode.REQUIRED, nullable = true) String shop,
		@Schema(description = "1차 자동 추출 지원 쇼핑몰 여부", example = "true", requiredMode = RequiredMode.REQUIRED) boolean supported,
		@Schema(description = "현재 사용자가 이미 저장한 상품 카드 ID (미저장이면 null)", example = "42", requiredMode = RequiredMode.REQUIRED, nullable = true) Long existingCardId) {
}
