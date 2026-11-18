package com.ject.ziper.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "소셜 로그인 응답")
public record OAuthLoginResponse(
		@Schema(description = "자체 서비스 Access Token (HS256 JWT)", example = "eyJhbGciOiJIUzI1NiJ9.access.token", requiredMode = RequiredMode.REQUIRED) String accessToken,
		@Schema(description = "자체 서비스 Refresh Token (회전 전용 HS256 JWT)", example = "eyJhbGciOiJIUzI1NiJ9.refresh.token", requiredMode = RequiredMode.REQUIRED) String refreshToken,
		@Schema(description = "이번 로그인에서 신규 자동 가입 처리되었는지 여부", example = "true", requiredMode = RequiredMode.REQUIRED) boolean isNewUser) {
}
