package com.ject.ziper.global.security.oauth;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

public record OAuthUserPrincipal(OAuthProvider provider, String providerId, String email) {
	public OAuthUserPrincipal {
		if (provider == null || providerId == null || providerId.isBlank()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		email = email == null || email.isBlank() ? null : email.trim();
	}
}
