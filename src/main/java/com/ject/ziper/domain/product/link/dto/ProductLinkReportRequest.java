package com.ject.ziper.domain.product.link.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "클라이언트 상품 링크 상태 보고 요청")
public record ProductLinkReportRequest(
		@Schema(description = "클라이언트가 관측한 링크 상태 (AVAILABLE | SOLD_OUT | REMOVED)", example = "SOLD_OUT", requiredMode = RequiredMode.REQUIRED) @NotBlank String status,
		@Schema(description = "클라이언트 관측 시각 (UTC ISO-8601)", example = "2026-04-19T12:00:00Z", requiredMode = RequiredMode.REQUIRED) @NotNull Instant observedAt) {
}
