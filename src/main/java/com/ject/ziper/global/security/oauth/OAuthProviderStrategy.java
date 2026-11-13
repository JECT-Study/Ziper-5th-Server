package com.ject.ziper.global.security.oauth;

public interface OAuthProviderStrategy {
	OAuthProvider provider();

	OAuthUserPrincipal authenticate(String token);
}
