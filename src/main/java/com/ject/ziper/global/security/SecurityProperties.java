package com.ject.ziper.global.security;

import java.net.URI;
import java.util.Base64;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security")
public record SecurityProperties(String jwtSecretBase64, String jwtIssuer, String jwtAudience,
		List<String> allowedOrigins) {
	@Override
	public String toString() {
		return "SecurityProperties[jwtSecretBase64=[REDACTED], jwtIssuer=" + jwtIssuer + ", jwtAudience=" + jwtAudience
				+ ", allowedOrigins=" + allowedOrigins + "]";
	}

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
	}
}
