package com.ject.ziper.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

class SecurityPropertiesTest {
	@Test
	void JWT비밀값이있는설정_문자열로변환하면_비밀값을마스킹하고공개설정을유지한다() {
		String secret = Base64.getEncoder().encodeToString(new byte[32]);
		var properties = new SecurityProperties(secret, "test-issuer", "test-audience", List.of("https://example.org"));
		assertThat(properties.toString()).doesNotContain(secret).contains("jwtSecretBase64=[REDACTED]",
				"jwtIssuer=test-issuer", "jwtAudience=test-audience", "allowedOrigins=[https://example.org]");
		assertThat(properties.jwtSecretBase64()).isEqualTo(secret);
	}

	@Test
	void OAuth비밀값이있는설정_문자열로변환하면_모든비밀값을마스킹한다() {
		String jwtSecret = Base64.getEncoder().encodeToString(new byte[32]);
		String applePrivateKey = "apple-private-key-secret";
		String naverSecret = "naver-client-secret";
		String kakaoSecret = "kakao-client-secret";
		var properties = new SecurityProperties(jwtSecret, "test-issuer", "test-audience",
				List.of("https://example.org"), 3600L, 1209600L, "https://appleid.apple.com",
				"https://appleid.apple.com/auth/keys", List.of("com.ject.ziper"),
				"https://appleid.apple.com/auth/token", "com.ject.ziper.web", "TEAM123456", "KEY1234567",
				applePrivateKey, "https://openapi.naver.com/v1/nid/me", "https://nid.naver.com/oauth2.0/token",
				"naver-id", naverSecret, "https://kapi.kakao.com/v2/user/me", "https://kauth.kakao.com/oauth/token",
				"kakao-id", kakaoSecret);
		String rendered = properties.toString();
		assertThat(rendered).doesNotContain(jwtSecret, applePrivateKey, naverSecret, kakaoSecret).contains(
				"jwtSecretBase64=[REDACTED]", "applePrivateKey=[REDACTED]", "naverClientSecret=[REDACTED]",
				"kakaoClientSecret=[REDACTED]", "appleAuthClientId=com.ject.ziper.web", "naverClientId=naver-id",
				"kakaoClientId=kakao-id");
	}
}
