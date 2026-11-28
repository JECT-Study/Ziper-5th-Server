package com.ject.ziper.domain.product.dto;

import java.time.Instant;

import com.ject.ziper.domain.product.entity.FallbackIcon;
import com.ject.ziper.domain.tag.dto.TagResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "구매 완료 상품 항목")
public record PurchasedProductItemResponse(
		@Schema(description = "상품 카드 ID", requiredMode = RequiredMode.REQUIRED) Long cardId,
		@Schema(description = "원래 쇼핑몰 상품 URL", requiredMode = RequiredMode.REQUIRED) String productUrl,
		@Schema(description = "쇼핑몰명 (없으면 null)", nullable = true) String shop,
		@Schema(description = "상품명", requiredMode = RequiredMode.REQUIRED) String productTitle,
		@Schema(description = "가격 (원)", requiredMode = RequiredMode.REQUIRED) Long price,
		@Schema(description = "표시 이미지 URL (저장소 썸네일 우선, 없으면 원본 이미지, 둘 다 없으면 null)", nullable = true) String imageUrl,
		@Schema(description = "이미지가 없을 때 사용할 대체 아이콘", nullable = true) FallbackIcon fallbackIcon,
		@Schema(description = "대표 태그 하나 (기본 태그 우선, ID 오름차순; 없으면 null)", nullable = true) TagResponse tag,
		@Schema(description = "상품 카드 최초 등록 시각", requiredMode = RequiredMode.REQUIRED) Instant createdAt) {
}
