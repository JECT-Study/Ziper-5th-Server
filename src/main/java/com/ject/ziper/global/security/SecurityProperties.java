package com.ject.ziper.global.security;

import java.net.URI;
import java.util.Base64;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("app.security")
public record SecurityProperties(String jwtSecretBase64, String jwtIssuer, String jwtAudience,
		List<String> allowedOrigins, Long accessTokenExpirationSeconds, Long refreshTokenExpirationSeconds,
		String appleIssuer, String appleJwksUri, List<String> appleClientIds, String kakaoUserInfoUri) {

	public SecurityProperties(String jwtSecretBase64, String jwtIssuer, String jwtAudience,
			List<String> allowedOrigins) {
		this(jwtSecretBase64, jwtIssuer, jwtAudience, allowedOrigins, null, null, null, null, null, null);
	}

	public SecurityProperties(String jwtSecretBase64, String jwtIssuer, String jwtAudience, List<String> allowedOrigins,
			Long accessTokenExpirationSeconds, Long refreshTokenExpirationSeconds, String appleIssuer,
			String appleJwksUri, List<String> appleClientIds) {
		this(jwtSecretBase64, jwtIssuer, jwtAudience, allowedOrigins, accessTokenExpirationSeconds,
				refreshTokenExpirationSeconds, appleIssuer, appleJwksUri, appleClientIds, null);
	}

	@Override
	public String toString() {
		return "SecurityProperties[jwtSecretBase64=[REDACTED], jwtIssuer=" + jwtIssuer + ", jwtAudience=" + jwtAudience
				+ ", allowedOrigins=" + allowedOrigins + ", accessTokenExpirationSeconds="
				+ accessTokenExpirationSeconds + ", refreshTokenExpirationSeconds=" + refreshTokenExpirationSeconds
				+ ", appleIssuer=" + appleIssuer + ", appleJwksUri=" + appleJwksUri + ", appleClientIds="
				+ appleClientIds + ", kakaoUserInfoUri=" + kakaoUserInfoUri + "]";
	}

	@ConstructorBinding
	public SecurityProperties {
		if (jwtSecretBase64 == null || jwtSecretBase64.isBlank()) {
			throw new IllegalArgumentException("app.security.jwt-secret-base64 is required");
		}
		byte[] key;
		try {
			key = Base64.getDecoder().decode(jwtSecretBase64);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("app.security.jwt-secret-base64 must be valid Base64");
		}
		if (key.length < 32) {
			throw new IllegalArgumentException("app.security.jwt-secret-base64 must decode to at least 32 bytes");
		}
		jwtIssuer = jwtIssuer == null ? "ziper-server" : jwtIssuer;
		jwtAudience = jwtAudience == null ? "ziper-api" : jwtAudience;
		if (jwtIssuer.isBlank() || jwtAudience.isBlank()) {
			throw new IllegalArgumentException("JWT issuer and audience must not be blank");
		}
		allowedOrigins = allowedOrigins == null
				? List.of()
				: allowedOrigins.stream().map(String::trim).filter(origin -> !origin.isEmpty()).toList();
		for (String origin : allowedOrigins) {
			URI uri = URI.create(origin);
			if (origin.contains("*") || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
					|| uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
					|| uri.getRawFragment() != null || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
				throw new IllegalArgumentException("CORS origins must be explicit HTTP(S) origins");
			}
		}
		accessTokenExpirationSeconds = accessTokenExpirationSeconds == null ? 3600L : accessTokenExpirationSeconds;
		refreshTokenExpirationSeconds = refreshTokenExpirationSeconds == null
				? 1209600L
				: refreshTokenExpirationSeconds;
		if (accessTokenExpirationSeconds <= 0 || refreshTokenExpirationSeconds <= 0) {
			throw new IllegalArgumentException("Token expiration seconds must be positive");
		}
		appleIssuer = appleIssuer == null || appleIssuer.isBlank() ? "https://appleid.apple.com" : appleIssuer.trim();
		appleJwksUri = appleJwksUri == null || appleJwksUri.isBlank()
				? "https://appleid.apple.com/auth/keys"
				: appleJwksUri.trim();
		appleClientIds = appleClientIds == null
				? List.of()
				: appleClientIds.stream().map(String::trim).filter(id -> !id.isEmpty()).toList();
		kakaoUserInfoUri = kakaoUserInfoUri == null || kakaoUserInfoUri.isBlank()
				? "https://kapi.kakao.com/v2/user/me"
				: kakaoUserInfoUri.trim();
	}
}
