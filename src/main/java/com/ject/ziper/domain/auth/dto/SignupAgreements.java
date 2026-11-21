package com.ject.ziper.domain.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "회원 가입 시 제출하는 약관 동의")
public record SignupAgreements(
		@Schema(description = "[필수] 서비스 이용약관", example = "true") @NotNull @AssertTrue Boolean termsOfService,
		@Schema(description = "[필수] 개인정보 수집 및 이용 동의", example = "true") @NotNull @AssertTrue Boolean privacyPolicy,
		@Schema(description = "[선택] 마케팅 정보 수신에 대한 선택", example = "false") @NotNull Boolean marketing) {
}
