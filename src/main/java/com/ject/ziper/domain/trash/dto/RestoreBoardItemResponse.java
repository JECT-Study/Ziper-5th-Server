package com.ject.ziper.domain.trash.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "복구 대상으로 선택할 수 있는 현재 활성 보드")
public record RestoreBoardItemResponse(
		@Schema(description = "활성 보드 ID", example = "2", requiredMode = RequiredMode.REQUIRED) Long boardId,
		@Schema(description = "보드 이름", example = "여름 코디", requiredMode = RequiredMode.REQUIRED) String name) {
}
