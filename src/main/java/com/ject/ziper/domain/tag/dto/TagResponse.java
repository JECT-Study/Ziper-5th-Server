package com.ject.ziper.domain.tag.dto;

import com.ject.ziper.domain.tag.entity.Tag;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "해시태그 응답")
public record TagResponse(
		@Schema(description = "해시태그 ID", example = "1", requiredMode = RequiredMode.REQUIRED) Long tagId,
		@Schema(description = "해시태그명 (# 제외)", example = "봄", requiredMode = RequiredMode.REQUIRED) String title,
		@Schema(description = "기본 제공 계절 태그 여부", example = "true", requiredMode = RequiredMode.REQUIRED) boolean isDefault) {

	public static TagResponse from(Tag tag) {
		return new TagResponse(tag.getId(), tag.getTitle(), tag.isDefault());
	}
}
