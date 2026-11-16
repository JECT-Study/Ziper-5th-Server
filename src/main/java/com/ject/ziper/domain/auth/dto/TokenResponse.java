package com.ject.ziper.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "토큰 재발급 응답")
public record TokenResponse(@Schema(description = "새로 발급된 자체 Access Token") String accessToken,
		@Schema(description = "새로 회전 발급된 자체 Refresh Token") String refreshToken) {
}
