package com.ject.ziper.domain.product.dto;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

public record PurchasedProductCursor(Instant createdAt, long cardId, long price) {
	private static final int MAX_CURSOR_LENGTH = 128;
	private static final Instant MIN_CREATED_AT = Instant.parse("-4713-11-24T00:00:00Z");
	private static final Instant CREATED_AT_UPPER_BOUND = Instant.parse("+294277-01-01T00:00:00Z");

	public static PurchasedProductCursor parse(String value, PurchasedProductSort sort) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		String prefix = prefix(sort);
		if (value.length() > MAX_CURSOR_LENGTH || !value.startsWith(prefix)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		String[] parts = value.substring(prefix.length()).split("\\|", -1);
		if (parts.length != 3) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		PurchasedProductCursor cursor;
		try {
			cursor = new PurchasedProductCursor(Instant.parse(parts[0]), Long.parseLong(parts[1]),
					Long.parseLong(parts[2]));
		} catch (DateTimeParseException | NumberFormatException exception) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		if (cursor.cardId() <= 0 || cursor.price() < 0 || cursor.createdAt().getNano() % 1000 != 0
				|| cursor.createdAt().isBefore(MIN_CREATED_AT) || !cursor.createdAt().isBefore(CREATED_AT_UPPER_BOUND)
				|| !cursor.encode(sort).equals(value)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return cursor;
	}

	public String encode(PurchasedProductSort sort) {
		return prefix(sort) + createdAt + "|" + cardId + "|" + price;
	}

	private static String prefix(PurchasedProductSort sort) {
		return "v1|purchased|" + sort.name() + "|";
	}
}
