package com.ject.ziper.domain.product.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "상품 URL 확인 및 정규화 요청")
public record ProductUrlCheckRequest(
		@Schema(description = "확인할 상품 URL (앱 WebView 최종 URL 또는 직접 입력 URL)", example = "https://m.musinsa.com/products/1234567?utm_source=kakao", requiredMode = RequiredMode.REQUIRED) @NotBlank String url) {
}
