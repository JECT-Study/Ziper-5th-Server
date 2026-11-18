package com.ject.ziper.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "로그아웃 요청")
public record LogoutRequest(
		@Schema(description = "폐기할 세션의 Refresh Token", example = "eyJhbGciOiJIUzI1NiJ9.refresh.token", requiredMode = RequiredMode.REQUIRED) @NotBlank String refreshToken) {
}
