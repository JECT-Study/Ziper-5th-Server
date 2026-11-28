package com.ject.ziper.domain.trash.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "휴지통 상품 카드 목록")
public record TrashProductListResponse(
		@Schema(description = "휴지통 상품 카드 목록", requiredMode = RequiredMode.REQUIRED) List<TrashProductItemResponse> items) {
}
