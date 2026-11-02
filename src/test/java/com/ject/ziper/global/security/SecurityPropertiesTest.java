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
}
