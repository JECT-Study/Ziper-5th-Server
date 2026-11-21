package com.ject.ziper.domain.board.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "보드 이름 수정 요청")
public record UpdateBoardRequest(
		@Schema(description = "변경할 보드 이름 (앞뒤 공백 제거 후 공백 포함 1~10자)", example = "여름 원피스", minLength = 1, maxLength = 10, requiredMode = RequiredMode.REQUIRED) String name) {
}
