package com.ject.ziper.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "소셜 Authorization Code 교환 로그인 요청")
public record OAuthCodeLoginRequest(
		@Schema(description = "프로바이더 인가 서버로부터 발급받은 Authorization Code", requiredMode = RequiredMode.REQUIRED) @NotBlank String code,
		@Schema(description = "인가 코드 요청 시 사용한 Redirect URI (Kakao·Apple 등 인가 시 명시한 경우 필수)", example = "https://app.ziper.dev/callback/kakao", requiredMode = RequiredMode.NOT_REQUIRED) String redirectUri,
		@Schema(description = "CSRF 방지용 state 값 (Naver 등 인가 시 전달한 경우 포함)", requiredMode = RequiredMode.NOT_REQUIRED) String state) {
}
