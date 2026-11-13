package com.ject.ziper.global.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Component
public class JwtTokenProvider {
	public static final String CLAIM_TOKEN_TYPE = "type";
	public static final String TOKEN_TYPE_ACCESS = "access";
	public static final String TOKEN_TYPE_REFRESH = "refresh";

	private final SecurityProperties properties;
	private final JwtEncoder jwtEncoder;
	private final JwtDecoder refreshJwtDecoder;
	private final Clock clock;

	@Autowired
	public JwtTokenProvider(SecurityProperties properties) {
		this(properties, Clock.systemUTC());
	}

	public JwtTokenProvider(SecurityProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		SecretKey key = new SecretKeySpec(Base64.getDecoder().decode(properties.jwtSecretBase64()), "HmacSHA256");
		this.jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(properties.jwtIssuer()),
				jwt -> jwt.getExpiresAt() != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()
						&& jwt.getAudience() != null && jwt.getAudience().contains(properties.jwtAudience())
						&& TOKEN_TYPE_REFRESH.equals(jwt.getClaimAsString(CLAIM_TOKEN_TYPE))
								? OAuth2TokenValidatorResult.success()
								: OAuth2TokenValidatorResult.failure(
										new OAuth2Error("invalid_token", "Invalid refresh token claims", null))));
		this.refreshJwtDecoder = decoder;
	}

	public String issueAccessToken(Long userId) {
		Instant now = clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer(properties.jwtIssuer())
				.audience(List.of(properties.jwtAudience())).subject(String.valueOf(userId)).issuedAt(now)
				.expiresAt(now.plusSeconds(properties.accessTokenExpirationSeconds()))
				.claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS).build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	public String issueRefreshToken(Long userId) {
		Instant now = clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder().issuer(properties.jwtIssuer())
				.audience(List.of(properties.jwtAudience())).subject(String.valueOf(userId))
				.id(UUID.randomUUID().toString()).issuedAt(now)
				.expiresAt(now.plusSeconds(properties.refreshTokenExpirationSeconds()))
				.claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH).build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	public Long parseRefreshTokenUserId(String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		try {
			Jwt jwt = refreshJwtDecoder.decode(refreshToken);
			return Long.parseLong(jwt.getSubject());
		} catch (JwtException | IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
	}

	public String hashToken(String token) {
		if (token == null || token.isBlank()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashed);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm not available", exception);
		}
	}
}
