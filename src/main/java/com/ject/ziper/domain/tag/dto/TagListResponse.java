package com.ject.ziper.domain.tag.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "해시태그 목록 응답")
public record TagListResponse(
		@Schema(description = "기본 계절 태그 4종 및 본인 커스텀 해시태그 목록 (기본 태그 우선, 생성순 정렬)", requiredMode = RequiredMode.REQUIRED) List<TagResponse> tags) {
}
