package com.ject.ziper.global.security.oauth;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

@Component
public class OAuthClientComposite {
	private final Map<OAuthProvider, OAuthProviderStrategy> strategies;

	public OAuthClientComposite(List<OAuthProviderStrategy> strategyList) {
		Map<OAuthProvider, OAuthProviderStrategy> map = new EnumMap<>(OAuthProvider.class);
		for (OAuthProviderStrategy strategy : strategyList) {
			map.put(strategy.provider(), strategy);
		}
		this.strategies = Map.copyOf(map);
	}

	public OAuthUserPrincipal authenticate(OAuthProvider provider, String token) {
		OAuthProviderStrategy strategy = strategies.get(provider);
		if (strategy == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return strategy.authenticate(token);
	}
}
