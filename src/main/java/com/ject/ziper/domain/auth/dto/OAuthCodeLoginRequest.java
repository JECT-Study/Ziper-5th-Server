package com.ject.ziper.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record OAuthCodeLoginRequest(@NotBlank String code, String redirectUri, String state) {
}
