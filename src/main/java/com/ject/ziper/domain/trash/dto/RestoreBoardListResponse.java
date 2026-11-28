package com.ject.ziper.domain.trash.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "휴지통 상품 복구 보드 목록")
public record RestoreBoardListResponse(
		@Schema(description = "현재 소유한 활성 보드 목록", requiredMode = RequiredMode.REQUIRED) List<RestoreBoardItemResponse> boards) {
}
