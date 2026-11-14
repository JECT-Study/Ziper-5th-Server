package com.ject.ziper.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record EmailCodeRequest(@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {
}
