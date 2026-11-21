package com.ject.ziper.domain.tag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "커스텀 해시태그 생성 요청")
public record CreateTagRequest(
		@Schema(description = "해시태그명 (선행 # 및 앞뒤 공백 제거 후 1~6자)", example = "데이트", requiredMode = RequiredMode.REQUIRED) String title) {
}
