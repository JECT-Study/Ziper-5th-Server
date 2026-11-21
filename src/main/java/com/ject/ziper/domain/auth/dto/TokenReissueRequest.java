package com.ject.ziper.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "토큰 재발급 요청")
public record TokenReissueRequest(
		@Schema(description = "본문 모드에서 필수인 Refresh Token", example = "eyJhbGciOiJIUzI1NiJ9.refresh.token") String refreshToken) {
}
