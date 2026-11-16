package com.ject.ziper.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

class AppleOAuthProviderStrategyTest {
	private static final String ISSUER = "https://appleid.apple.com";
	private static final List<String> ALLOWED_CLIENT_IDS = List.of("com.ject.ziper", "com.ject.ziper.web");
	private static RSAPublicKey publicKey;
	private static RSAPrivateKey privateKey;

	@BeforeAll
	static void setUpKeys() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair pair = generator.generateKeyPair();
		publicKey = (RSAPublicKey) pair.getPublic();
		privateKey = (RSAPrivateKey) pair.getPrivate();
	}

	@Test
	void iOS네이티브와웹Audience를가진AppleID토큰_검증을요청하면_유저식별자와이메일을추출한다() throws Exception {
		AppleOAuthProviderStrategy strategy = createStrategy();

		for (String clientId : ALLOWED_CLIENT_IDS) {
			String token = signToken(defaultClaims(clientId), privateKey);
			OAuthUserPrincipal principal = strategy.authenticate(token);
			assertThat(principal.provider()).isEqualTo(OAuthProvider.APPLE);
			assertThat(principal.providerId()).isEqualTo("apple-user-001");
			assertThat(principal.email()).isEqualTo("user@privaterelay.appleid.com");
		}
	}

	@Test
	void 만료되거나잘못된발급자또는허용되지않은Audience토큰_검증을요청하면_UNAUTHORIZED예외를던진다() throws Exception {
		AppleOAuthProviderStrategy strategy = createStrategy();

		List<JWTClaimsSet.Builder> invalidClaims = List.of(
				defaultClaims("com.ject.ziper").expirationTime(Date.from(Instant.now().minusSeconds(300))),
				defaultClaims("com.ject.ziper").issuer("https://evil.example.com"), defaultClaims("com.other.app"),
				defaultClaims("com.ject.ziper").subject(" "));

		for (JWTClaimsSet.Builder claims : invalidClaims) {
			String token = signToken(claims, privateKey);
			assertThatThrownBy(() -> strategy.authenticate(token)).isInstanceOf(BusinessException.class)
					.extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
		}
		assertThatThrownBy(() -> strategy.authenticate(" ")).isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
		assertThatThrownBy(() -> strategy.authenticate("invalid.jwt.value")).isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
	}

	private AppleOAuthProviderStrategy createStrategy() {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
				.signatureAlgorithm(SignatureAlgorithm.RS256).build();
		decoder.setJwtValidator(AppleOAuthProviderStrategy.createValidator(ISSUER, ALLOWED_CLIENT_IDS));
		return new AppleOAuthProviderStrategy(decoder);
	}

	private JWTClaimsSet.Builder defaultClaims(String audience) {
		return new JWTClaimsSet.Builder().issuer(ISSUER).audience(audience).subject("apple-user-001")
				.claim("email", "user@privaterelay.appleid.com").issueTime(Date.from(Instant.now().minusSeconds(10)))
				.expirationTime(Date.from(Instant.now().plusSeconds(600)));
	}

	private String signToken(JWTClaimsSet.Builder claims, RSAPrivateKey key) throws Exception {
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
		jwt.sign(new RSASSASigner(key));
		return jwt.serialize();
	}
}
