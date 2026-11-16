package com.ject.ziper.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

class JwtTokenProviderTest {
	private static final String SECRET = Base64.getEncoder()
			.encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

	@Test
	void 유효한보안설정_액세스토큰과리프레시토큰을발급하면_용도별클레임과해시를정확히생성한다() {
		SecurityProperties properties = new SecurityProperties(SECRET, "ziper-server", "ziper-api", List.of(), 3600L,
				1209600L, "https://appleid.apple.com", "https://appleid.apple.com/auth/keys",
				List.of("com.ject.ziper"));
		JwtTokenProvider provider = new JwtTokenProvider(properties);
		JwtDecoder accessDecoder = new SecurityConfiguration().jwtDecoder(properties);

		String accessToken = provider.issueAccessToken(42L);
		String refreshToken = provider.issueRefreshToken(42L);

		Jwt decodedAccess = accessDecoder.decode(accessToken);
		assertThat(decodedAccess.getSubject()).isEqualTo("42");
		assertThat(decodedAccess.getClaimAsString(JwtTokenProvider.CLAIM_TOKEN_TYPE))
				.isEqualTo(JwtTokenProvider.TOKEN_TYPE_ACCESS);
		assertThat(provider.parseRefreshTokenUserId(refreshToken)).isEqualTo(42L);
		assertThat(provider.hashToken(refreshToken)).hasSize(64).isNotEqualTo(refreshToken);
	}

	@Test
	void 액세스토큰이나만료된리프레시토큰_리프레시토큰으로파싱하면_UNAUTHORIZED예외를던진다() {
		SecurityProperties properties = new SecurityProperties(SECRET, "ziper-server", "ziper-api", List.of(), 3600L,
				60L, "https://appleid.apple.com", "https://appleid.apple.com/auth/keys", List.of("com.ject.ziper"));
		Clock pastClock = Clock.fixed(Instant.now().minusSeconds(3600), ZoneOffset.UTC);
		JwtTokenProvider expiredProvider = new JwtTokenProvider(properties, pastClock);
		JwtTokenProvider currentProvider = new JwtTokenProvider(properties);

		String expiredRefresh = expiredProvider.issueRefreshToken(10L);
		String accessToken = currentProvider.issueAccessToken(10L);

		for (String invalid : List.of("", " ", "broken.jwt.token", expiredRefresh, accessToken)) {
			assertThatThrownBy(() -> currentProvider.parseRefreshTokenUserId(invalid))
					.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
		}
	}
}
