package com.ject.ziper.domain.trash.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "휴지통 상품 카드 항목")
public record TrashProductItemResponse(
		@Schema(description = "상품 카드 ID", example = "1", requiredMode = RequiredMode.REQUIRED) Long cardId,
		@Schema(description = "쇼핑몰명 (없으면 null)", example = "무신사", nullable = true) String shop,
		@Schema(description = "상품명", example = "오버핏 코튼 티셔츠", requiredMode = RequiredMode.REQUIRED) String productTitle,
		@Schema(description = "가격 (원)", example = "29000", requiredMode = RequiredMode.REQUIRED) Long price,
		@Schema(description = "표시할 이미지 URL (없으면 null)", nullable = true) String imageUrl,
		@Schema(description = "휴지통 조회·복구 가능 기간의 남은 일수 (올림)", example = "12", requiredMode = RequiredMode.REQUIRED) int daysRemaining) {
}
