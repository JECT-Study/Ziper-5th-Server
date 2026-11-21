package com.ject.ziper.domain.board.dto;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

public record BoardProductCursor(Instant addedAt, long boardCardId, long price) {
	private static final int MAX_CURSOR_LENGTH = 128;
	private static final Instant MIN_ADDED_AT = Instant.parse("-4713-11-24T00:00:00Z");
	private static final Instant ADDED_AT_UPPER_BOUND = Instant.parse("+294277-01-01T00:00:00Z");

	public static BoardProductCursor parse(String value, long boardId, BoardProductSort sort) {
		if (value == null || value.isEmpty()) {
			return null;
		}
		String prefix = prefix(boardId, sort);
		if (value.length() > MAX_CURSOR_LENGTH || !value.startsWith(prefix)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		String[] keys = value.substring(prefix.length()).split("\\|", -1);
		if (keys.length != 3) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		BoardProductCursor cursor;
		try {
			cursor = new BoardProductCursor(Instant.parse(keys[0]), Long.parseLong(keys[1]), Long.parseLong(keys[2]));
		} catch (DateTimeParseException | NumberFormatException exception) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		// PostgreSQL TIMESTAMPTZ 범위와 정밀도를 확인한 뒤 JDBC에 전달한다.
		if (cursor.boardCardId() <= 0 || cursor.price() < 0 || cursor.addedAt().getNano() % 1000 != 0
				|| cursor.addedAt().isBefore(MIN_ADDED_AT) || !cursor.addedAt().isBefore(ADDED_AT_UPPER_BOUND)
				|| !cursor.encode(boardId, sort).equals(value)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return cursor;
	}

	public String encode(long boardId, BoardProductSort sort) {
		return prefix(boardId, sort) + addedAt + "|" + boardCardId + "|" + price;
	}

	private static String prefix(long boardId, BoardProductSort sort) {
		return "v1|" + boardId + "|" + sort.name() + "|";
	}
}
