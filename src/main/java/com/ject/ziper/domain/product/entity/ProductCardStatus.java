package com.ject.ziper.domain.product.entity;

import java.util.Locale;
import java.util.Optional;

public enum ProductCardStatus {
	PLANNED, PURCHASED;

	public static Optional<ProductCardStatus> fromValue(String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		return switch (value.trim().toUpperCase(Locale.ROOT)) {
			case "PLANNED" -> Optional.of(PLANNED);
			case "PURCHASED" -> Optional.of(PURCHASED);
			default -> Optional.empty();
		};
	}
}
