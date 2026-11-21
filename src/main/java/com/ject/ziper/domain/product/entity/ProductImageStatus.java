package com.ject.ziper.domain.product.entity;

import java.util.Locale;
import java.util.Optional;

public enum ProductImageStatus {
	NONE, PENDING, READY, FAILED;

	public static Optional<ProductImageStatus> fromValue(String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		return switch (value.trim().toUpperCase(Locale.ROOT)) {
			case "NONE" -> Optional.of(NONE);
			case "PENDING" -> Optional.of(PENDING);
			case "READY" -> Optional.of(READY);
			case "FAILED" -> Optional.of(FAILED);
			default -> Optional.empty();
		};
	}
}
