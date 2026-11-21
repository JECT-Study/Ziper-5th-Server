package com.ject.ziper.domain.product.link.entity;

import java.util.Locale;
import java.util.Optional;

public enum ProductLinkStatus {
	AVAILABLE, SOLD_OUT, REMOVED, UNKNOWN;

	public static Optional<ProductLinkStatus> fromReportValue(String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		return switch (value.trim().toUpperCase(Locale.ROOT)) {
			case "AVAILABLE" -> Optional.of(AVAILABLE);
			case "SOLD_OUT" -> Optional.of(SOLD_OUT);
			case "REMOVED" -> Optional.of(REMOVED);
			default -> Optional.empty();
		};
	}

	public static Optional<ProductLinkStatus> fromValue(String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		return switch (value.trim().toUpperCase(Locale.ROOT)) {
			case "AVAILABLE" -> Optional.of(AVAILABLE);
			case "SOLD_OUT" -> Optional.of(SOLD_OUT);
			case "REMOVED" -> Optional.of(REMOVED);
			case "UNKNOWN" -> Optional.of(UNKNOWN);
			default -> Optional.empty();
		};
	}
}
