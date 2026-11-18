package com.ject.ziper.domain.auth.dto;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "이메일 회원 가입 요청")
public record EmailSignupRequest(
		@Schema(description = "인증을 완료한 이메일 인증 흐름 ID", example = "3f1c2a4e-8b7d-4c6a-9e1f-2a3b4c5d6e7f") @NotNull UUID flowId,
		@Schema(description = "영문·숫자·특수문자를 각각 1자 이상 포함한 8~64자 (공백 불가)", example = "abcd1234!") @NotNull String password,
		@Schema(description = "한글·영문·숫자 2~10자 닉네임", example = "감자") @NotNull String nickname,
		@Schema(description = "약관 동의") @NotNull @Valid Agreements agreements) {

	@Schema(description = "약관 동의 항목")
	public record Agreements(@Schema(description = "[필수] 서비스 이용약관") @NotNull @AssertTrue Boolean termsOfService,
			@Schema(description = "[필수] 개인정보 수집 및 이용 동의") @NotNull @AssertTrue Boolean privacyPolicy,
			@Schema(description = "[선택] 마케팅 정보 수신 동의") @NotNull Boolean marketing) {
	}
}
