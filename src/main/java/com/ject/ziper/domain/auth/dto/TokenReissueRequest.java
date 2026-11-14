package com.ject.ziper.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record TokenReissueRequest(@NotBlank String refreshToken) {
}
