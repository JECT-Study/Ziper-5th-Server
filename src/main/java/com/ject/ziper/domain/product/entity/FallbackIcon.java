package com.ject.ziper.domain.product.entity;

import java.util.Locale;
import java.util.Optional;

public enum FallbackIcon {
	TOP, PANTS, SKIRT, ACCESSORY, BEAUTY;

	public static Optional<FallbackIcon> fromValue(String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		return switch (value.trim().toUpperCase(Locale.ROOT)) {
			case "TOP" -> Optional.of(TOP);
			case "PANTS" -> Optional.of(PANTS);
			case "SKIRT" -> Optional.of(SKIRT);
			case "ACCESSORY" -> Optional.of(ACCESSORY);
			case "BEAUTY" -> Optional.of(BEAUTY);
			default -> Optional.empty();
		};
	}
}
