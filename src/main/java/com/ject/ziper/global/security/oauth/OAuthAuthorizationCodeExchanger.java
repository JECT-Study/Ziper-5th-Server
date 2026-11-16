package com.ject.ziper.global.security.oauth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.DefaultMapOAuth2AccessTokenResponseConverter;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.security.SecurityProperties;

/**
 * Exchanges OAuth authorization codes for provider tokens via Spring Security
 * RestClient.
 */
@Component
public class OAuthAuthorizationCodeExchanger {
	private static final String APPLE_REGISTRATION_ID = "apple";
	private static final String NAVER_REGISTRATION_ID = "naver";
	private static final String KAKAO_REGISTRATION_ID = "kakao";
	private static final String ID_TOKEN_PARAMETER = "id_token";
	private static final String STATE_ATTRIBUTE = "state_param";
	private static final String PLACEHOLDER_REDIRECT_URI = "https://localhost/callback";

	private final RestClientAuthorizationCodeTokenResponseClient tokenResponseClient;
	private final SecurityProperties properties;
	private final AppleClientSecretGenerator appleClientSecretGenerator;

	@Autowired
	public OAuthAuthorizationCodeExchanger(RestClient.Builder restClientBuilder, SecurityProperties properties,
			AppleClientSecretGenerator appleClientSecretGenerator) {
		this.properties = properties;
		this.appleClientSecretGenerator = appleClientSecretGenerator;
		this.tokenResponseClient = createTokenResponseClient(restClientBuilder);
	}

	public String exchange(OAuthProvider provider, String code, String redirectUri, String state) {
		String trimmedCode = code == null ? "" : code.trim();
		if (trimmedCode.isEmpty()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		String trimmedRedirectUri = redirectUri == null ? "" : redirectUri.trim();
		String trimmedState = state == null ? "" : state.trim();
		ClientRegistration registration = createClientRegistration(provider, trimmedRedirectUri);
		var authorizationRequestBuilder = OAuth2AuthorizationRequest.authorizationCode()
				.authorizationUri(registration.getProviderDetails().getTokenUri()).clientId(registration.getClientId());
		if (!trimmedRedirectUri.isEmpty()) {
			authorizationRequestBuilder.redirectUri(trimmedRedirectUri);
		}
		if (!trimmedState.isEmpty()) {
			authorizationRequestBuilder.state(trimmedState);
			authorizationRequestBuilder.attributes(attributes -> attributes.put(STATE_ATTRIBUTE, trimmedState));
		}
		OAuth2AuthorizationRequest authorizationRequest = authorizationRequestBuilder.build();
		String responseRedirectUri = trimmedRedirectUri.isEmpty() ? PLACEHOLDER_REDIRECT_URI : trimmedRedirectUri;
		var authorizationResponseBuilder = OAuth2AuthorizationResponse.success(trimmedCode)
				.redirectUri(responseRedirectUri);
		if (!trimmedState.isEmpty()) {
			authorizationResponseBuilder.state(trimmedState);
		}
		var grantRequest = new OAuth2AuthorizationCodeGrantRequest(registration,
				new OAuth2AuthorizationExchange(authorizationRequest, authorizationResponseBuilder.build()));
		try {
			OAuth2AccessTokenResponse response = tokenResponseClient.getTokenResponse(grantRequest);
			if (provider == OAuthProvider.APPLE) {
				Object idToken = response.getAdditionalParameters().get(ID_TOKEN_PARAMETER);
				if (!(idToken instanceof String idTokenValue) || idTokenValue.isBlank()) {
					throw new BusinessException(ErrorCode.UNAUTHORIZED);
				}
				return idTokenValue;
			}
			String accessToken = response.getAccessToken().getTokenValue();
			if (accessToken == null || accessToken.isBlank()) {
				throw new BusinessException(ErrorCode.UNAUTHORIZED);
			}
			return accessToken;
		} catch (OAuth2AuthorizationException | RestClientException | IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
	}

	private RestClientAuthorizationCodeTokenResponseClient createTokenResponseClient(RestClient.Builder builder) {
		var responseConverter = new OAuth2AccessTokenResponseHttpMessageConverter();
		var defaultMapConverter = new DefaultMapOAuth2AccessTokenResponseConverter();
		responseConverter.setAccessTokenResponseConverter(source -> {
			Object rawAccessToken = source.get(OAuth2ParameterNames.ACCESS_TOKEN);
			if (!(rawAccessToken instanceof String accessTokenValue) || accessTokenValue.isBlank()) {
				throw new IllegalArgumentException("Missing access_token in OAuth token response");
			}
			Map<String, Object> normalized = new LinkedHashMap<>(source);
			Object tokenType = normalized.get(OAuth2ParameterNames.TOKEN_TYPE);
			if (!(tokenType instanceof String typeValue) || typeValue.isBlank()) {
				normalized.put(OAuth2ParameterNames.TOKEN_TYPE, TokenType.BEARER.getValue());
			}
			return defaultMapConverter.convert(normalized);
		});
		RestClient restClient = builder.clone().messageConverters(converters -> {
			converters.clear();
			converters.add(new FormHttpMessageConverter());
			converters.add(responseConverter);
		}).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build();
		var client = new RestClientAuthorizationCodeTokenResponseClient();
		client.setRestClient(restClient);
		client.setParametersConverter(this::customizeParameters);
		return client;
	}

	private MultiValueMap<String, String> customizeParameters(OAuth2AuthorizationCodeGrantRequest grantRequest) {
		MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
		String registrationId = grantRequest.getClientRegistration().getRegistrationId();
		if (APPLE_REGISTRATION_ID.equals(registrationId)) {
			String clientSecret = appleClientSecretGenerator.generate(properties.appleIssuer(),
					properties.appleAuthClientId(), properties.appleTeamId(), properties.appleKeyId(),
					properties.applePrivateKey());
			parameters.set(OAuth2ParameterNames.CLIENT_SECRET, clientSecret);
		} else if (NAVER_REGISTRATION_ID.equals(registrationId)) {
			String stateValue = grantRequest.getAuthorizationExchange().getAuthorizationRequest()
					.getAttribute(STATE_ATTRIBUTE);
			if (stateValue != null && !stateValue.isBlank()) {
				parameters.set(OAuth2ParameterNames.STATE, stateValue);
			}
		}
		return parameters;
	}

	private ClientRegistration createClientRegistration(OAuthProvider provider, String redirectUri) {
		String registrationRedirectUri = redirectUri.isEmpty() ? PLACEHOLDER_REDIRECT_URI : redirectUri;
		return switch (provider) {
			case APPLE -> buildAppleRegistration(registrationRedirectUri);
			case NAVER -> buildNaverRegistration(registrationRedirectUri);
			case KAKAO -> buildKakaoRegistration(registrationRedirectUri);
		};
	}

	private ClientRegistration buildAppleRegistration(String redirectUri) {
		if (properties.appleAuthClientId().isBlank()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return ClientRegistration.withRegistrationId(APPLE_REGISTRATION_ID).clientId(properties.appleAuthClientId())
				.clientSecret("").clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri(redirectUri)
				.authorizationUri(properties.appleTokenUri()).tokenUri(properties.appleTokenUri()).build();
	}

	private ClientRegistration buildNaverRegistration(String redirectUri) {
		if (properties.naverClientId().isBlank() || properties.naverClientSecret().isBlank()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return ClientRegistration.withRegistrationId(NAVER_REGISTRATION_ID).clientId(properties.naverClientId())
				.clientSecret(properties.naverClientSecret())
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri(redirectUri)
				.authorizationUri(properties.naverTokenUri()).tokenUri(properties.naverTokenUri()).build();
	}

	private ClientRegistration buildKakaoRegistration(String redirectUri) {
		if (properties.kakaoClientId().isBlank()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		ClientAuthenticationMethod authMethod = properties.kakaoClientSecret().isBlank()
				? ClientAuthenticationMethod.NONE
				: ClientAuthenticationMethod.CLIENT_SECRET_POST;
		var builder = ClientRegistration.withRegistrationId(KAKAO_REGISTRATION_ID).clientId(properties.kakaoClientId())
				.clientAuthenticationMethod(authMethod)
				.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri(redirectUri)
				.authorizationUri(properties.kakaoTokenUri()).tokenUri(properties.kakaoTokenUri());
		if (!properties.kakaoClientSecret().isBlank()) {
			builder.clientSecret(properties.kakaoClientSecret());
		}
		return builder.build();
	}
}
