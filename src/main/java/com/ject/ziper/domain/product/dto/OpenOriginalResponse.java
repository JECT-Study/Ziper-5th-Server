package com.ject.ziper.domain.product.dto;

import java.time.Instant;

import com.ject.ziper.domain.product.entity.ProductCard;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "원본 쇼핑몰 이동 이벤트 응답")
public record OpenOriginalResponse(
		@Schema(description = "상품 카드 ID", example = "1", requiredMode = RequiredMode.REQUIRED) Long cardId,
		@Schema(description = "이동할 정규화 상품 URL", example = "https://www.musinsa.com/products/1234567", requiredMode = RequiredMode.REQUIRED) String productUrl,
		@Schema(description = "초기화된 재발견 단계 (항상 0)", example = "0", requiredMode = RequiredMode.REQUIRED) int rediscoveryStage,
		@Schema(description = "갱신된 마지막 상호작용 시각 (UTC ISO-8601)", example = "2026-11-21T12:00:00Z", requiredMode = RequiredMode.REQUIRED) Instant lastInteractedAt,
		@Schema(description = "재계산된 다음 재발견 예정 시각 (+14일, UTC ISO-8601)", example = "2026-12-05T12:00:00Z", requiredMode = RequiredMode.REQUIRED) Instant nextRediscoveryAt) {

	public static OpenOriginalResponse from(ProductCard card) {
		return new OpenOriginalResponse(card.getId(), card.getProductUrl(), card.getRediscoveryStage(),
				card.getLastInteractedAt(), card.getNextRediscoveryAt());
	}
}
