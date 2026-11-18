package com.ject.ziper.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "이메일 인증번호 발송 요청")
public record EmailVerificationRequest(
		@Schema(description = "인증번호를 받을 이메일 주소", example = "member@example.com", requiredMode = RequiredMode.REQUIRED) @NotBlank @Email @Size(max = 255) String email) {
}
