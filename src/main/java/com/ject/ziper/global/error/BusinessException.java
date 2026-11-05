package com.ject.ziper.global.error;

import java.util.Objects;

public final class BusinessException extends RuntimeException {
	private final ErrorCode errorCode;

	public BusinessException(ErrorCode errorCode) {
		super(Objects.requireNonNull(errorCode).detail());
		if (!errorCode.status().is4xxClientError()) {
			throw new IllegalArgumentException("Business errors require a 4xx status");
		}
		this.errorCode = errorCode;
	}

	public ErrorCode errorCode() {
		return errorCode;
	}
}
