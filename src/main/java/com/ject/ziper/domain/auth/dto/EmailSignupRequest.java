package com.ject.ziper.domain.auth.dto;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "이메일 회원 가입 요청")
public record EmailSignupRequest(
		@Schema(description = "인증을 완료한 이메일 인증 흐름 ID", example = "3f1c2a4e-8b7d-4c6a-9e1f-2a3b4c5d6e7f", requiredMode = RequiredMode.REQUIRED) @NotNull UUID flowId,
		@Schema(description = "영문·숫자·특수문자를 각각 1자 이상 포함한 8~64자 (공백 불가)", example = "abcd1234!", requiredMode = RequiredMode.REQUIRED) @NotNull String password,
		@Schema(description = "한글·영문·숫자 2~10자 닉네임", example = "감자", requiredMode = RequiredMode.REQUIRED) @NotNull String nickname,
		@Schema(description = "약관 동의", requiredMode = RequiredMode.REQUIRED) @NotNull @Valid SignupAgreements agreements) {
}
