package com.ject.ziper.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "이메일 인증번호 확인 요청")
public record EmailCodeRequest(
		@Schema(description = "6자리 숫자 인증번호", example = "123456", requiredMode = RequiredMode.REQUIRED) @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {
}
