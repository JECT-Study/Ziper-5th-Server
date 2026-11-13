package com.ject.ziper.global.security.oauth;

import java.util.Locale;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

public enum OAuthProvider {
	APPLE, NAVER;

	public static OAuthProvider from(String value) {
		if (value == null || value.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		try {
			return OAuthProvider.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
	}
}
