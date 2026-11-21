package com.ject.ziper.domain.product.entity;

import java.util.Locale;
import java.util.Optional;

public enum MetadataSource {
	CLIENT, MANUAL;

	public static Optional<MetadataSource> fromValue(String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		return switch (value.trim().toUpperCase(Locale.ROOT)) {
			case "CLIENT" -> Optional.of(CLIENT);
			case "MANUAL" -> Optional.of(MANUAL);
			default -> Optional.empty();
		};
	}
}
