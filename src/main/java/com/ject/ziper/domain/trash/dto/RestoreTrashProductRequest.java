package com.ject.ziper.domain.trash.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "휴지통 상품을 복구할 활성 보드 선택")
public record RestoreTrashProductRequest(
		@Schema(description = "선택한 본인 활성 보드 ID (1개 이상)", example = "[1, 2]", requiredMode = RequiredMode.REQUIRED) List<Long> targetBoardIds) {
}
