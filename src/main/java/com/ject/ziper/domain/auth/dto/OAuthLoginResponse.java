package com.ject.ziper.domain.auth.dto;

public record OAuthLoginResponse(String accessToken, String refreshToken, boolean isNewUser) {
}
