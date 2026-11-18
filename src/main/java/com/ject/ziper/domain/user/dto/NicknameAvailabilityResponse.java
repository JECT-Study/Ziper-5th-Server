package com.ject.ziper.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "닉네임 사용 가능 여부 응답")
public record NicknameAvailabilityResponse(
		@Schema(description = "사용 가능 여부", example = "true", requiredMode = RequiredMode.REQUIRED) boolean available) {
}
