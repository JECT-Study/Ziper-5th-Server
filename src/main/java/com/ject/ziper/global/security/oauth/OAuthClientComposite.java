package com.ject.ziper.global.security.oauth;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

@Component
public class OAuthClientComposite {
	private final Map<OAuthProvider, OAuthProviderStrategy> strategies;
	private final OAuthAuthorizationCodeExchanger codeExchanger;

	public OAuthClientComposite(List<OAuthProviderStrategy> strategyList) {
		this(strategyList, null);
	}

	@Autowired
	public OAuthClientComposite(List<OAuthProviderStrategy> strategyList,
			OAuthAuthorizationCodeExchanger codeExchanger) {
		Map<OAuthProvider, OAuthProviderStrategy> map = new EnumMap<>(OAuthProvider.class);
		for (OAuthProviderStrategy strategy : strategyList) {
			map.put(strategy.provider(), strategy);
		}
		this.strategies = Map.copyOf(map);
		this.codeExchanger = codeExchanger;
	}

	public OAuthUserPrincipal authenticate(OAuthProvider provider, String token) {
		OAuthProviderStrategy strategy = strategies.get(provider);
		if (strategy == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		return strategy.authenticate(token);
	}

	public OAuthUserPrincipal authenticateWithCode(OAuthProvider provider, String code, String redirectUri,
			String state) {
		if (codeExchanger == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		String token = codeExchanger.exchange(provider, code, redirectUri, state);
		return authenticate(provider, token);
	}
}
