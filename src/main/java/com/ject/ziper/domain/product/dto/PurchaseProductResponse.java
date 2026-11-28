package com.ject.ziper.domain.product.dto;

import com.ject.ziper.domain.product.entity.ProductCardStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "상품 구매 완료 전환 응답")
public record PurchaseProductResponse(
		@Schema(description = "상품 카드 ID", requiredMode = RequiredMode.REQUIRED) Long cardId,
		@Schema(description = "구매 상태", example = "PURCHASED", requiredMode = RequiredMode.REQUIRED) ProductCardStatus status,
		@Schema(description = "이 상품이 연결된 활성 보드 수", requiredMode = RequiredMode.REQUIRED) int affectedBoardCount) {
}
