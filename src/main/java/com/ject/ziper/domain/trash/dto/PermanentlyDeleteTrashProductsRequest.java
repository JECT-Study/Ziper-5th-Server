package com.ject.ziper.domain.trash.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "휴지통 상품 여러 개를 영구 삭제하는 요청")
public record PermanentlyDeleteTrashProductsRequest(
		@Schema(description = "영구 삭제할 본인 휴지통 상품 카드 ID (1개 이상)", example = "[1, 2]", requiredMode = RequiredMode.REQUIRED) List<Long> productIds) {
}
