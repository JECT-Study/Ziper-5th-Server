package com.ject.ziper.global.security.oauth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.security.SecurityProperties;

@Component
public class KakaoOAuthProviderStrategy implements OAuthProviderStrategy {
	private final RestClient restClient;
	private final String userInfoUri;

	@Autowired
	public KakaoOAuthProviderStrategy(RestClient.Builder restClientBuilder, SecurityProperties properties) {
		this(restClientBuilder.build(), properties.kakaoUserInfoUri());
	}

	public KakaoOAuthProviderStrategy(RestClient restClient, String userInfoUri) {
		this.restClient = restClient;
		this.userInfoUri = userInfoUri;
	}

	@Override
	public OAuthProvider provider() {
		return OAuthProvider.KAKAO;
	}

	@Override
	public OAuthUserPrincipal authenticate(String token) {
		if (token == null || token.isBlank()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		try {
			KakaoUserResponse response = restClient.get().uri(userInfoUri)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(KakaoUserResponse.class);
			if (response == null || response.id() == null) {
				throw new BusinessException(ErrorCode.UNAUTHORIZED);
			}
			String email = response.kakaoAccount() == null ? null : response.kakaoAccount().email();
			return new OAuthUserPrincipal(OAuthProvider.KAKAO, String.valueOf(response.id()), email);
		} catch (RestClientException | IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
	}

	private record KakaoUserResponse(Long id, @JsonProperty("kakao_account") KakaoAccount kakaoAccount) {
	}

	private record KakaoAccount(String email) {
	}
}
