package com.ject.ziper.domain.product.dto;

import java.util.Locale;
import java.util.Optional;

public enum PurchasedProductSort {
	RECENT, OLDEST, PRICE_ASC, PRICE_DESC;

	public static Optional<PurchasedProductSort> fromValue(String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}
}
