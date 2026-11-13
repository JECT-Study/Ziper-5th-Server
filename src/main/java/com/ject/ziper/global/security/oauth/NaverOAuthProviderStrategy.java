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
public class NaverOAuthProviderStrategy implements OAuthProviderStrategy {
	private static final String SUCCESS_RESULT_CODE = "00";

	private final RestClient restClient;
	private final String userInfoUri;

	@Autowired
	public NaverOAuthProviderStrategy(RestClient.Builder restClientBuilder, SecurityProperties properties) {
		this(restClientBuilder.build(), properties.naverUserInfoUri());
	}

	public NaverOAuthProviderStrategy(RestClient restClient, String userInfoUri) {
		this.restClient = restClient;
		this.userInfoUri = userInfoUri;
	}

	@Override
	public OAuthProvider provider() {
		return OAuthProvider.NAVER;
	}

	@Override
	public OAuthUserPrincipal authenticate(String token) {
		if (token == null || token.isBlank()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		try {
			NaverUserResponse response = restClient.get().uri(userInfoUri)
					.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).retrieve().body(NaverUserResponse.class);
			if (response == null || !SUCCESS_RESULT_CODE.equals(response.resultCode()) || response.profile() == null) {
				throw new BusinessException(ErrorCode.UNAUTHORIZED);
			}
			return new OAuthUserPrincipal(OAuthProvider.NAVER, response.profile().id(), response.profile().email());
		} catch (RestClientException | IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
	}

	private record NaverUserResponse(@JsonProperty("resultcode") String resultCode,
			@JsonProperty("response") NaverProfile profile) {
	}

	private record NaverProfile(String id, String email) {
	}
}
