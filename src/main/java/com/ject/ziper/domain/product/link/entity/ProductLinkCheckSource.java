package com.ject.ziper.domain.product.link.entity;

import java.util.Locale;
import java.util.Optional;

public enum ProductLinkCheckSource {
	SERVER, CLIENT;

	public static Optional<ProductLinkCheckSource> fromValue(String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		return switch (value.trim().toUpperCase(Locale.ROOT)) {
			case "SERVER" -> Optional.of(SERVER);
			case "CLIENT" -> Optional.of(CLIENT);
			default -> Optional.empty();
		};
	}
}
