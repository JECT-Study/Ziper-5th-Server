package com.ject.ziper.domain.auth.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "소셜 로그인 또는 가입 결과. 가입 필요 응답에는 자체 Access/Refresh Token을 발급하지 않습니다.")
public record OAuthLoginResponse(AuthType authType, UUID signupToken,
		@Schema(description = "자체 Access Token") String accessToken,
		@Schema(description = "본문 모드에서만 반환하는 Refresh Token") String refreshToken,
		@Schema(description = "이번 요청에서 가입을 완료했는지 여부") boolean isNewUser, AgreementStatus agreements) {

	public enum AuthType {
		LOGIN, SIGNUP_REQUIRED
	}

	public static OAuthLoginResponse signupRequired(UUID signupToken) {
		return new OAuthLoginResponse(AuthType.SIGNUP_REQUIRED, signupToken, null, null, false, null);
	}

	public static OAuthLoginResponse login(TokenResponse tokens, boolean isNewUser) {
		return new OAuthLoginResponse(AuthType.LOGIN, null, tokens.accessToken(), tokens.refreshToken(), isNewUser,
				tokens.agreements());
	}

	public OAuthLoginResponse withoutRefreshToken() {
		return new OAuthLoginResponse(authType, signupToken, accessToken, null, isNewUser, agreements);
	}
}
