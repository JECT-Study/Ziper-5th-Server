package com.ject.ziper.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "토큰 재발급 요청")
public record TokenReissueRequest(
		@Schema(description = "유효한 자체 Refresh Token", requiredMode = RequiredMode.REQUIRED) @NotBlank String refreshToken) {
}
