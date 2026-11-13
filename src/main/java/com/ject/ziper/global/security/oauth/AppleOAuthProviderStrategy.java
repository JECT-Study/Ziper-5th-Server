package com.ject.ziper.global.security.oauth;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.security.SecurityProperties;

@Component
public class AppleOAuthProviderStrategy implements OAuthProviderStrategy {
	private final JwtDecoder appleJwtDecoder;

	@Autowired
	public AppleOAuthProviderStrategy(SecurityProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.appleJwksUri())
				.jwsAlgorithm(SignatureAlgorithm.RS256).build();
		decoder.setJwtValidator(createValidator(properties.appleIssuer(), properties.appleClientIds()));
		this.appleJwtDecoder = decoder;
	}

	public AppleOAuthProviderStrategy(JwtDecoder appleJwtDecoder) {
		this.appleJwtDecoder = appleJwtDecoder;
	}

	public static OAuth2TokenValidator<Jwt> createValidator(String issuer, List<String> allowedClientIds) {
		return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
				jwt -> jwt.getExpiresAt() != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()
						&& jwt.getAudience() != null && !allowedClientIds.isEmpty()
						&& jwt.getAudience().stream().anyMatch(allowedClientIds::contains)
								? OAuth2TokenValidatorResult.success()
								: OAuth2TokenValidatorResult
										.failure(new OAuth2Error("invalid_token", "Invalid Apple ID token", null)));
	}

	@Override
	public OAuthProvider provider() {
		return OAuthProvider.APPLE;
	}

	@Override
	public OAuthUserPrincipal authenticate(String token) {
		if (token == null || token.isBlank()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		try {
			Jwt jwt = appleJwtDecoder.decode(token);
			return new OAuthUserPrincipal(OAuthProvider.APPLE, jwt.getSubject(), jwt.getClaimAsString("email"));
		} catch (JwtException | IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
	}
}
