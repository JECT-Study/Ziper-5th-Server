package com.ject.ziper.domain.product.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "구매 완료 상품 목록 응답")
public record PurchasedProductListResponse(
		@Schema(description = "전체 구매 완료 상품 수", requiredMode = RequiredMode.REQUIRED) int totalCount,
		@Schema(description = "현재 페이지 상품 목록", requiredMode = RequiredMode.REQUIRED) List<PurchasedProductItemResponse> items,
		@Schema(description = "다음 페이지 커서 (없으면 null)", nullable = true) String nextCursor,
		@Schema(description = "다음 페이지 존재 여부", requiredMode = RequiredMode.REQUIRED) boolean hasNext) {
}
