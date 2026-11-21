package com.ject.ziper.domain.product.link.dto;

import java.time.Instant;

import com.ject.ziper.domain.product.link.entity.ProductLinkCheckSource;
import com.ject.ziper.domain.product.link.entity.ProductLinkStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상품 링크 상태 응답")
public record ProductLinkStatusResponse(
		@Schema(description = "링크 상태 (AVAILABLE | SOLD_OUT | REMOVED | UNKNOWN)", example = "UNKNOWN") ProductLinkStatus status,
		@Schema(description = "마지막 상태 확인 시각 (UTC ISO-8601, 미확인 시 null)", example = "2026-04-19T12:00:00Z", nullable = true) Instant checkedAt,
		@Schema(description = "마지막 상태 확인 출처 (SERVER | CLIENT, 미확인 시 null)", example = "CLIENT", nullable = true) ProductLinkCheckSource source) {
}
