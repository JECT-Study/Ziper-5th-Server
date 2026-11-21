package com.ject.ziper.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

public record EmailLoginRequest(@Schema(example = "member@example.com") @NotBlank @Size(max = 255) @Email String email,
		@Schema(example = "abcd1234!") @NotBlank @Pattern(regexp = "[!-~]{1,64}") String password) {
}
