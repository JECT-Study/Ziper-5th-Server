package com.ject.ziper.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "토큰 재발급 응답")
public record TokenResponse(
		@Schema(description = "새로 발급된 자체 Access Token", example = "eyJhbGciOiJIUzI1NiJ9.new.access.token", requiredMode = RequiredMode.REQUIRED) String accessToken,
		@Schema(description = "본문 모드에서만 반환하는 Refresh Token", example = "eyJhbGciOiJIUzI1NiJ9.new.refresh.token") @JsonInclude(JsonInclude.Include.NON_NULL) String refreshToken,
		AgreementStatus agreements) {

	public TokenResponse withoutRefreshToken() {
		return new TokenResponse(accessToken, null, agreements);
	}
}
