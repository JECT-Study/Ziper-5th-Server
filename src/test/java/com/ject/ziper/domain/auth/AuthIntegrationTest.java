package com.ject.ziper.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.domain.auth.entity.Session;
import com.ject.ziper.domain.auth.repository.SessionRepository;
import com.ject.ziper.domain.board.entity.ProductBoard;
import com.ject.ziper.domain.board.repository.ProductBoardRepository;
import com.ject.ziper.domain.user.entity.User;
import com.ject.ziper.domain.user.repository.OAuthUserRepository;
import com.ject.ziper.domain.user.repository.UserRepository;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;
import com.ject.ziper.global.security.SecurityProperties;
import com.ject.ziper.global.security.oauth.AppleOAuthProviderStrategy;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthIntegrationTest extends PostgresTestSupport {
	private static RSAPublicKey applePublicKey;
	private static RSAPrivateKey applePrivateKey;
	private static HttpServer kakaoUserInfoServer;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private OAuthUserRepository oauthUserRepository;

	@Autowired
	private ProductBoardRepository productBoardRepository;

	@Autowired
	private SessionRepository sessionRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private JwtDecoder accessJwtDecoder;

	@DynamicPropertySource
	static void kakaoProperties(DynamicPropertyRegistry registry) throws IOException {
		startKakaoServer();
		int port = kakaoUserInfoServer.getAddress().getPort();
		registry.add("app.security.kakao-user-info-uri", () -> "http://127.0.0.1:" + port + "/v2/user/me");
	}

	@BeforeAll
	static void initAppleKeys() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair pair = generator.generateKeyPair();
		applePublicKey = (RSAPublicKey) pair.getPublic();
		applePrivateKey = (RSAPrivateKey) pair.getPrivate();
	}

	@AfterAll
	static void stopKakaoServer() {
		if (kakaoUserInfoServer != null) {
			kakaoUserInfoServer.stop(0);
			kakaoUserInfoServer = null;
		}
	}

	@BeforeEach
	void cleanTables() {
		sessionRepository.deleteAll();
		productBoardRepository.deleteAll();
		userRepository.deleteAll();
		oauthUserRepository.deleteAll();
	}

	@Test
	void 미가입유저의AppleID토큰_소셜로그인과재발급과로그아웃을수행하면_자동가입후토큰을회전하고세션을정리한다() throws Exception {
		String appleIdToken = createAppleIdToken("apple-sub-100", "apple100@privaterelay.appleid.com",
				"com.ject.ziper");

		String firstResponse = mvc
				.perform(post("/api/v1/auth/oauth/apple").contentType(MediaType.APPLICATION_JSON)
						.header(HttpHeaders.USER_AGENT, "Expo/iOS-18.0")
						.content("{\"token\":\"" + appleIdToken + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("isNewUser").value(true))
				.andExpect(jsonPath("accessToken").isNotEmpty()).andExpect(jsonPath("refreshToken").isNotEmpty())
				.andReturn().getResponse().getContentAsString();

		JsonNode firstJson = objectMapper.readTree(firstResponse);
		String firstAccessToken = firstJson.get("accessToken").asText();
		String firstRefreshToken = firstJson.get("refreshToken").asText();

		List<User> users = userRepository.findAll();
		assertThat(users).hasSize(1);
		User savedUser = users.getFirst();
		assertThat(savedUser.getEmail()).isEqualTo("apple100@privaterelay.appleid.com");
		assertThat(savedUser.getNickname()).matches("^Ziper\\d{5}$");

		List<ProductBoard> boards = productBoardRepository.findAllByUserId(savedUser.getId());
		assertThat(boards).hasSize(1);
		assertThat(boards.getFirst().getName()).isEqualTo(ProductBoard.DEFAULT_BOARD_NAME);
		assertThat(boards.getFirst().isModifiable()).isFalse();

		List<Session> sessions = sessionRepository.findAll();
		assertThat(sessions).hasSize(1);
		assertThat(sessions.getFirst().getTokenHash()).isEqualTo(jwtTokenProvider.hashToken(firstRefreshToken))
				.isNotEqualTo(firstRefreshToken);
		assertThat(accessJwtDecoder.decode(firstAccessToken).getSubject()).isEqualTo(String.valueOf(savedUser.getId()));

		String webAppleToken = createAppleIdToken("apple-sub-100", "apple100@privaterelay.appleid.com",
				"com.ject.ziper.web");
		mvc.perform(post("/api/v1/auth/oauth/apple").contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.USER_AGENT, "Mozilla/5.0").content("{\"token\":\"" + webAppleToken + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("isNewUser").value(false));
		assertThat(userRepository.count()).isOne();
		assertThat(sessionRepository.count()).isEqualTo(2);

		String reissueResponse = mvc
				.perform(post("/api/v1/auth/reissue").contentType(MediaType.APPLICATION_JSON)
						.header(HttpHeaders.USER_AGENT, "Expo/iOS-18.1")
						.content("{\"refreshToken\":\"" + firstRefreshToken + "\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("accessToken").isNotEmpty())
				.andExpect(jsonPath("refreshToken").isNotEmpty()).andReturn().getResponse().getContentAsString();

		JsonNode reissueJson = objectMapper.readTree(reissueResponse);
		String rotatedRefreshToken = reissueJson.get("refreshToken").asText();
		assertThat(rotatedRefreshToken).isNotEqualTo(firstRefreshToken);

		mvc.perform(post("/api/v1/auth/reissue").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + firstRefreshToken + "\"}")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("code").value("UNAUTHORIZED"));

		mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + rotatedRefreshToken + "\"}")).andExpect(status().isNoContent());

		mvc.perform(post("/api/v1/auth/reissue").contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"" + rotatedRefreshToken + "\"}")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("code").value("UNAUTHORIZED"));
	}

	@Test
	void 이미가입된이메일과중복되거나잘못된프로바이더요청_소셜로그인을시도하면_4xx오류를반환한다() throws Exception {
		String firstToken = createAppleIdToken("apple-sub-1", "dup@privaterelay.appleid.com", "com.ject.ziper");
		mvc.perform(post("/api/v1/auth/oauth/apple").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + firstToken + "\"}")).andExpect(status().isOk());

		String conflictToken = createAppleIdToken("apple-sub-2", "dup@privaterelay.appleid.com", "com.ject.ziper");
		mvc.perform(post("/api/v1/auth/oauth/apple").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + conflictToken + "\"}")).andExpect(status().isConflict())
				.andExpect(jsonPath("code").value("CONFLICT"));

		mvc.perform(post("/api/v1/auth/oauth/unknown").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + firstToken + "\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("code").value("INVALID_REQUEST"));
	}

	@Test
	void 이메일없는Kakao액세스토큰_복수사용자가소셜로그인과재로그인을수행하면_NULL이메일로모두가입되고고유제약없이토큰을발급한다() throws Exception {
		String firstResponse = mvc
				.perform(post("/api/v1/auth/oauth/kakao").contentType(MediaType.APPLICATION_JSON)
						.header(HttpHeaders.USER_AGENT, "Mozilla/5.0")
						.content("{\"token\":\"kakao-token-no-email-1\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("isNewUser").value(true))
				.andExpect(jsonPath("accessToken").isNotEmpty()).andExpect(jsonPath("refreshToken").isNotEmpty())
				.andReturn().getResponse().getContentAsString();

		mvc.perform(post("/api/v1/auth/oauth/kakao").contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.USER_AGENT, "Mozilla/5.0").content("{\"token\":\"kakao-token-no-email-2\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("isNewUser").value(true))
				.andExpect(jsonPath("accessToken").isNotEmpty()).andExpect(jsonPath("refreshToken").isNotEmpty());

		List<User> users = userRepository.findAll();
		assertThat(users).hasSize(2);
		assertThat(users).allSatisfy(user -> {
			assertThat(user.getEmail()).isNull();
			assertThat(user.getNickname()).matches("^Ziper\\d{5}$");
			assertThat(productBoardRepository.findAllByUserId(user.getId())).hasSize(1);
		});

		String firstAccessToken = objectMapper.readTree(firstResponse).get("accessToken").asText();
		Long firstUserId = Long.valueOf(accessJwtDecoder.decode(firstAccessToken).getSubject());

		String reloginResponse = mvc
				.perform(post("/api/v1/auth/oauth/kakao").contentType(MediaType.APPLICATION_JSON)
						.header(HttpHeaders.USER_AGENT, "Expo/iOS-18.0")
						.content("{\"token\":\"kakao-token-no-email-1\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("isNewUser").value(false)).andReturn().getResponse()
				.getContentAsString();

		String reloginAccessToken = objectMapper.readTree(reloginResponse).get("accessToken").asText();
		assertThat(Long.valueOf(accessJwtDecoder.decode(reloginAccessToken).getSubject())).isEqualTo(firstUserId);
		assertThat(userRepository.count()).isEqualTo(2);
		assertThat(sessionRepository.count()).isEqualTo(3);

		mvc.perform(post("/api/v1/auth/oauth/kakao").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"kakao-token-with-email\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("isNewUser").value(true));

		String conflictAppleToken = createAppleIdToken("apple-sub-dup", "shared@example.com", "com.ject.ziper");
		mvc.perform(post("/api/v1/auth/oauth/apple").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + conflictAppleToken + "\"}")).andExpect(status().isConflict())
				.andExpect(jsonPath("code").value("CONFLICT"));

		mvc.perform(post("/api/v1/auth/oauth/kakao").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"invalid-kakao-token\"}")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("code").value("UNAUTHORIZED"));
	}

	private static synchronized void startKakaoServer() throws IOException {
		if (kakaoUserInfoServer != null) {
			return;
		}
		kakaoUserInfoServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		kakaoUserInfoServer.createContext("/v2/user/me", exchange -> {
			String authorization = exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION);
			int statusCode = 200;
			String body;
			if ("Bearer kakao-token-no-email-1".equals(authorization)) {
				body = "{\"id\":500001,\"kakao_account\":{}}";
			} else if ("Bearer kakao-token-no-email-2".equals(authorization)) {
				body = "{\"id\":500002}";
			} else if ("Bearer kakao-token-with-email".equals(authorization)) {
				body = "{\"id\":500003,\"kakao_account\":{\"email\":\"shared@example.com\"}}";
			} else {
				statusCode = 401;
				body = "{\"code\":-401,\"msg\":\"invalid token\"}";
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
			exchange.sendResponseHeaders(statusCode, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		kakaoUserInfoServer.start();
	}

	private String createAppleIdToken(String subject, String email, String audience) throws Exception {
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer("https://appleid.apple.com").audience(audience)
				.subject(subject).claim("email", email).issueTime(Date.from(Instant.now().minusSeconds(5)))
				.expirationTime(Date.from(Instant.now().plusSeconds(600))).build();
		SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
		signedJwt.sign(new RSASSASigner(applePrivateKey));
		return signedJwt.serialize();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestAppleOAuthConfiguration {
		@Bean
		@Primary
		AppleOAuthProviderStrategy testAppleOAuthProviderStrategy(SecurityProperties properties) {
			NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(applePublicKey)
					.signatureAlgorithm(SignatureAlgorithm.RS256).build();
			decoder.setJwtValidator(
					AppleOAuthProviderStrategy.createValidator(properties.appleIssuer(), properties.appleClientIds()));
			return new AppleOAuthProviderStrategy(decoder);
		}
	}
}
