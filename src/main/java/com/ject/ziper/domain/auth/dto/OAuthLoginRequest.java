package com.ject.ziper.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record OAuthLoginRequest(@NotBlank String token) {
}
