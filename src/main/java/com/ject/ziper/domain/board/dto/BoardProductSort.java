package com.ject.ziper.domain.board.dto;

import java.util.Locale;
import java.util.Optional;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "보드 상세 상품 목록 정렬 기준 (RECENT | OLDEST | PRICE_ASC | PRICE_DESC)")
public enum BoardProductSort {
	RECENT, OLDEST, PRICE_ASC, PRICE_DESC;

	public static Optional<BoardProductSort> fromValue(String value) {
		if (value == null || value.isBlank()) {
			return Optional.empty();
		}
		return switch (value.trim().toUpperCase(Locale.ROOT)) {
			case "RECENT" -> Optional.of(RECENT);
			case "OLDEST" -> Optional.of(OLDEST);
			case "PRICE_ASC" -> Optional.of(PRICE_ASC);
			case "PRICE_DESC" -> Optional.of(PRICE_DESC);
			default -> Optional.empty();
		};
	}
}
