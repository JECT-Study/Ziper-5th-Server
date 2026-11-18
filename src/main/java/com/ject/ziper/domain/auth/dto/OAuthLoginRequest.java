package com.ject.ziper.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "소셜 토큰 직접 로그인 요청")
public record OAuthLoginRequest(
		@Schema(description = "Apple ID Token(JWT) 또는 Naver/Kakao Access Token", example = "eyJraWQiOiJlWGF1...", requiredMode = RequiredMode.REQUIRED) @NotBlank String token) {
}
