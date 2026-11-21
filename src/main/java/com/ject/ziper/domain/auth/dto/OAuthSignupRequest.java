package com.ject.ziper.domain.auth.dto;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

public record OAuthSignupRequest(@Schema(description = "소셜 로그인 응답에서 발급받은 일회용 가입 토큰") @NotNull UUID signupToken,
		@NotNull @Valid SignupAgreements agreements) {
}
