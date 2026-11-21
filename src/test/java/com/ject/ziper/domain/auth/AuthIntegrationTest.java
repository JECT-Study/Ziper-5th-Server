package com.ject.ziper.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jakarta.servlet.http.Cookie;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
	private static final String WEB_ORIGIN = "https://app.ziper.cc";
	private static final String COOKIE_MODE = "X-Refresh-Token-Mode";
	private static final String COOKIE_NAME = "ziper_refresh_token";
	private static final String OAUTH_SIGNUP = "/api/v1/auth/oauth/signup";
	private static RSAPublicKey applePublicKey;
	private static RSAPrivateKey applePrivateKey;
	private static ECPrivateKey appleClientSecretEcPrivateKey;
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

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@DynamicPropertySource
	static void kakaoProperties(DynamicPropertyRegistry registry) throws Exception {
		initAppleKeys();
		startKakaoServer();
		int port = kakaoUserInfoServer.getAddress().getPort();
		registry.add("app.security.allowed-origins", () -> WEB_ORIGIN);
		registry.add("app.security.kakao-user-info-uri", () -> "http://127.0.0.1:" + port + "/v2/user/me");
		registry.add("app.security.kakao-token-uri", () -> "http://127.0.0.1:" + port + "/oauth/token");
		registry.add("app.security.kakao-client-id", () -> "kakao-client-id");
		registry.add("app.security.kakao-client-secret", () -> "kakao-client-secret");
		registry.add("app.security.naver-user-info-uri", () -> "http://127.0.0.1:" + port + "/v1/nid/me");
		registry.add("app.security.naver-token-uri", () -> "http://127.0.0.1:" + port + "/oauth2.0/token");
		registry.add("app.security.naver-client-id", () -> "naver-client-id");
		registry.add("app.security.naver-client-secret", () -> "naver-client-secret");
		registry.add("app.security.apple-token-uri", () -> "http://127.0.0.1:" + port + "/auth/token");
		registry.add("app.security.apple-auth-client-id", () -> "com.ject.ziper.web");
		registry.add("app.security.apple-team-id", () -> "TEAM123456");
		registry.add("app.security.apple-key-id", () -> "KEY1234567");
		registry.add("app.security.apple-private-key",
				() -> "-----BEGIN PRIVATE KEY-----\\n"
						+ Base64.getEncoder().encodeToString(appleClientSecretEcPrivateKey.getEncoded())
						+ "\\n-----END PRIVATE KEY-----");
	}

	@BeforeAll
	static synchronized void initAppleKeys() throws Exception {
		if (applePublicKey != null && applePrivateKey != null && appleClientSecretEcPrivateKey != null) {
			return;
		}
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair pair = generator.generateKeyPair();
		applePublicKey = (RSAPublicKey) pair.getPublic();
		applePrivateKey = (RSAPrivateKey) pair.getPrivate();
		KeyPairGenerator ecGenerator = KeyPairGenerator.getInstance("EC");
		ecGenerator.initialize(new ECGenParameterSpec("secp256r1"));
		appleClientSecretEcPrivateKey = (ECPrivateKey) ecGenerator.generateKeyPair().getPrivate();
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
		jdbc.update("DELETE FROM ziper.oauth_signup_tickets");
		sessionRepository.deleteAll();
		productBoardRepository.deleteAll();
		userRepository.deleteAll();
		oauthUserRepository.deleteAll();
	}

	@Test
	void 미가입유저의AppleID토큰_약관동의후가입하고재발급과로그아웃을수행하면_토큰을회전하고세션을정리한다() throws Exception {
		String appleIdToken = createAppleIdToken("apple-sub-100", "apple100@privaterelay.appleid.com",
				"com.ject.ziper");

		String signupToken = pendingSignup(mvc.perform(post("/api/v1/auth/oauth/apple")
				.contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.USER_AGENT, "Expo/iOS-18.0")
				.content("{\"token\":\"" + appleIdToken + "\"}")).andReturn());
		assertThat(userRepository.count()).isZero();
		assertThat(oauthUserRepository.count()).isZero();
		assertThat(sessionRepository.count()).isZero();
		mvc.perform(signupRequest(signupToken, false, true)).andExpect(status().isBadRequest());
		mvc.perform(signupRequest(signupToken, true, false)).andExpect(status().isBadRequest());
		assertThat(userRepository.count()).isZero();
		assertThat(sessionRepository.count()).isZero();

		JsonNode firstJson = signup(signupToken);
		assertThat(firstJson.path("agreements").path("termsOfService").asBoolean()).isTrue();
		assertThat(firstJson.path("agreements").path("privacyPolicy").asBoolean()).isTrue();
		assertThat(firstJson.path("agreements").path("marketing").asBoolean()).isFalse();
		assertThat(firstJson.path("agreements").path("termsAgreedAt").asText()).isNotBlank();
		assertThat(firstJson.path("agreements").path("privacyAgreedAt").asText()).isNotBlank();
		mvc.perform(signupRequest(signupToken, true, true)).andExpect(status().isUnauthorized());
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
				.andExpect(status().isOk()).andExpect(jsonPath("authType").value("LOGIN"))
				.andExpect(jsonPath("isNewUser").value(false));
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
		signup(pendingSignup(mvc.perform(post("/api/v1/auth/oauth/apple").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + firstToken + "\"}")).andReturn()));

		String conflictToken = createAppleIdToken("apple-sub-2", "dup@privaterelay.appleid.com", "com.ject.ziper");
		mvc.perform(post("/api/v1/auth/oauth/apple").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + conflictToken + "\"}")).andExpect(status().isConflict())
				.andExpect(jsonPath("code").value("EMAIL_ALREADY_REGISTERED"));

		mvc.perform(post("/api/v1/auth/oauth/unknown").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + firstToken + "\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("code").value("INVALID_REQUEST"));
	}

	@Test
	void 이메일로가입한회원과대소문자만다른소셜이메일_소셜로그인을시도하면_연결하지않고이메일중복으로거부한다() throws Exception {
		userRepository.save(User.createEmailUser("shared@example.com", "{bcrypt}hash", "이메일회원", false, Instant.now()));

		String appleToken = createAppleIdToken("apple-sub-email-dup", "Shared@Example.com", "com.ject.ziper");
		mvc.perform(post("/api/v1/auth/oauth/apple").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + appleToken + "\"}")).andExpect(status().isConflict())
				.andExpect(jsonPath("code").value("EMAIL_ALREADY_REGISTERED"));

		assertThat(oauthUserRepository.count()).isZero();
		assertThat(userRepository.count()).isOne();
	}

	@Test
	void 대소문자가섞인소셜이메일_동의후가입하면_소문자로정규화해저장한다() throws Exception {
		String appleToken = createAppleIdToken("apple-sub-mixed", " Mixed.Case@Example.com", "com.ject.ziper");
		signup(pendingSignup(mvc.perform(post("/api/v1/auth/oauth/apple").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + appleToken + "\"}")).andReturn()));

		assertThat(userRepository.findAll()).singleElement()
				.satisfies(user -> assertThat(user.getEmail()).isEqualTo("mixed.case@example.com"));
	}

	@Test
	void 동일소셜가입토큰이동시에들어왔을때_가입을처리하면_일회용토큰으로한세션만생성한다() throws Exception {
		String appleToken = createAppleIdToken("apple-sub-concurrent", "concurrent@example.com", "com.ject.ziper");
		String ticket = pendingSignup(mvc.perform(post("/api/v1/auth/oauth/apple")
				.contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + appleToken + "\"}")).andReturn());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		List<Integer> responses;
		try {
			Callable<Integer> signup = () -> mvc.perform(signupRequest(ticket, true, true)).andReturn().getResponse()
					.getStatus();
			List<Future<Integer>> results = executor.invokeAll(List.of(signup, signup));
			responses = List.of(results.getFirst().get(), results.getLast().get());
		} finally {
			executor.shutdownNow();
		}

		assertThat(responses).containsExactlyInAnyOrder(201, 401);
		assertThat(userRepository.count()).isOne();
		assertThat(oauthUserRepository.count()).isOne();
		assertThat(sessionRepository.count()).isOne();
	}

	@Test
	void 이메일없는Kakao액세스토큰_복수사용자가소셜로그인과재로그인을수행하면_NULL이메일로모두가입되고고유제약없이토큰을발급한다() throws Exception {
		JsonNode firstResponse = signup(pendingSignup(mvc.perform(post("/api/v1/auth/oauth/kakao")
				.contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.USER_AGENT, "Mozilla/5.0")
				.content("{\"token\":\"kakao-token-no-email-1\"}")).andReturn()));

		signup(pendingSignup(mvc.perform(post("/api/v1/auth/oauth/kakao").contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.USER_AGENT, "Mozilla/5.0").content("{\"token\":\"kakao-token-no-email-2\"}"))
				.andReturn()));

		List<User> users = userRepository.findAll();
		assertThat(users).hasSize(2);
		assertThat(users).allSatisfy(user -> {
			assertThat(user.getEmail()).isNull();
			assertThat(user.getNickname()).matches("^Ziper\\d{5}$");
			assertThat(productBoardRepository.findAllByUserId(user.getId())).hasSize(1);
		});

		String firstAccessToken = firstResponse.get("accessToken").asText();
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

		signup(pendingSignup(mvc.perform(post("/api/v1/auth/oauth/kakao").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"kakao-token-with-email\"}")).andReturn()));

		String conflictAppleToken = createAppleIdToken("apple-sub-dup", "shared@example.com", "com.ject.ziper");
		mvc.perform(post("/api/v1/auth/oauth/apple").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + conflictAppleToken + "\"}")).andExpect(status().isConflict())
				.andExpect(jsonPath("code").value("EMAIL_ALREADY_REGISTERED"));

		mvc.perform(post("/api/v1/auth/oauth/kakao").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"invalid-kakao-token\"}")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("code").value("UNAUTHORIZED"));
	}

	@Test
	void 유효한KakaoNaverApple인가코드_코드를교환하고약관동의를제출하면_가입후자체토큰을발급한다() throws Exception {
		signup(pendingSignup(mvc
				.perform(post("/api/v1/auth/oauth/kakao/code").contentType(MediaType.APPLICATION_JSON)
						.header(HttpHeaders.USER_AGENT, "Mozilla/5.0").content(
								"{\"code\":\"kakao-code-1\",\"redirectUri\":\"https://app.ziper.dev/callback/kakao\"}"))
				.andReturn()));

		signup(pendingSignup(mvc.perform(post("/api/v1/auth/oauth/naver/code").contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.USER_AGENT, "Mozilla/5.0")
				.content("{\"code\":\"naver-code-1\",\"state\":\"naver-state-1\"}")).andReturn()));

		signup(pendingSignup(mvc
				.perform(post("/api/v1/auth/oauth/apple/code").contentType(MediaType.APPLICATION_JSON)
						.header(HttpHeaders.USER_AGENT, "Mozilla/5.0").content(
								"{\"code\":\"apple-code-1\",\"redirectUri\":\"https://app.ziper.dev/callback/apple\"}"))
				.andReturn()));

		assertThat(userRepository.count()).isEqualTo(3);
		assertThat(sessionRepository.count()).isEqualTo(3);

		mvc.perform(post("/api/v1/auth/oauth/kakao/code").contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"invalid-code\"}")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("code").value("UNAUTHORIZED"));
	}

	@Test
	void 허용웹출처_소셜가입부터재발급과로그아웃까지요청하면_리프레시토큰을쿠키로회전하고만료시킨다() throws Exception {
		mvc.perform(options("/api/v1/auth/reissue").header(HttpHeaders.ORIGIN, WEB_ORIGIN)
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, COOKIE_MODE)).andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WEB_ORIGIN))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, COOKIE_MODE));

		String ticket = pendingSignup(mvc.perform(
				post("/api/v1/auth/oauth/kakao").header(HttpHeaders.ORIGIN, WEB_ORIGIN).header(COOKIE_MODE, "cookie")
						.contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"kakao-token-no-email-1\"}"))
				.andReturn());
		MvcResult registered = mvc
				.perform(signupRequest(ticket, true, true).header(HttpHeaders.ORIGIN, WEB_ORIGIN).header(COOKIE_MODE,
						"cookie"))
				.andExpect(status().isCreated()).andExpect(jsonPath("accessToken").isNotEmpty())
				.andExpect(jsonPath("refreshToken").doesNotExist())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn();
		assertThat(registered.getResponse().getHeader(HttpHeaders.SET_COOKIE))
				.contains("Path=/api/v1/auth", "Max-Age=1209600", "HttpOnly", "Secure", "SameSite=Lax")
				.doesNotContain("Domain=");
		Cookie refresh = registered.getResponse().getCookie(COOKIE_NAME);
		assertThat(refresh).isNotNull();

		mvc.perform(post("/api/v1/auth/reissue").header(COOKIE_MODE, "cookie").cookie(refresh))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/auth/reissue").header(HttpHeaders.ORIGIN, "https://untrusted.ziper.cc")
				.header(COOKIE_MODE, "cookie").cookie(refresh)).andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/auth/reissue").header(HttpHeaders.ORIGIN, WEB_ORIGIN).cookie(refresh))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/v1/auth/reissue").header(HttpHeaders.ORIGIN, WEB_ORIGIN).header(COOKIE_MODE, "cookie")
				.cookie(refresh).contentType(MediaType.APPLICATION_JSON)
				.content("{\"refreshToken\":\"another-token\"}")).andExpect(status().isBadRequest());

		MvcResult rotated = mvc
				.perform(post("/api/v1/auth/reissue").header(HttpHeaders.ORIGIN, WEB_ORIGIN)
						.header(COOKIE_MODE, "cookie").cookie(refresh))
				.andExpect(status().isOk()).andExpect(jsonPath("accessToken").isNotEmpty())
				.andExpect(jsonPath("refreshToken").doesNotExist())
				.andExpect(jsonPath("agreements.termsOfService").value(true)).andReturn();
		Cookie rotatedCookie = rotated.getResponse().getCookie(COOKIE_NAME);
		assertThat(rotatedCookie.getValue()).isNotEqualTo(refresh.getValue());
		mvc.perform(post("/api/v1/auth/reissue").header(HttpHeaders.ORIGIN, WEB_ORIGIN).header(COOKIE_MODE, "cookie")
				.cookie(refresh)).andExpect(status().isUnauthorized());
		MvcResult loggedOut = mvc
				.perform(post("/api/v1/auth/logout").header(HttpHeaders.ORIGIN, WEB_ORIGIN)
						.header(COOKIE_MODE, "cookie").cookie(rotatedCookie))
				.andExpect(status().isNoContent()).andReturn();
		assertThat(loggedOut.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0",
				"Path=/api/v1/auth");
		assertThat(sessionRepository.count()).isZero();
	}

	@Test
	void 만료된소셜가입토큰이주어졌을때_가입을요청하면_계정과세션을생성하지않는다() throws Exception {
		String ticket = pendingSignup(
				mvc.perform(post("/api/v1/auth/oauth/kakao").contentType(MediaType.APPLICATION_JSON)
						.content("{\"token\":\"kakao-token-no-email-1\"}")).andReturn());
		jdbc.update("UPDATE ziper.oauth_signup_tickets SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'");
		mvc.perform(signupRequest(ticket, true, true)).andExpect(status().isUnauthorized());
		assertThat(userRepository.count()).isZero();
		assertThat(sessionRepository.count()).isZero();
	}

	@Test
	void 이메일계정과소셜계정이있을때_이메일로그인을요청하면_올바른비밀번호로만약관상태와토큰을받는다() throws Exception {
		User user = userRepository.save(User.createEmailUser("login@example.com", passwordEncoder.encode("valid123!"),
				"이메일가입자", false, Instant.now().minusSeconds(60)));
		signup(pendingSignup(mvc.perform(post("/api/v1/auth/oauth/kakao").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"kakao-token-with-email\"}")).andReturn()));

		mvc.perform(emailLogin("LOGIN@example.com", "valid123!")).andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("refreshToken").isNotEmpty())
				.andExpect(jsonPath("agreements.privacyPolicy").value(true));
		assertThat(userRepository.findById(user.getId()).orElseThrow().getLastLoggedAt())
				.isAfter(user.getLastLoggedAt());

		for (String email : List.of("login@example.com", "missing@example.com", "shared@example.com")) {
			mvc.perform(emailLogin(email, "wrong123!")).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("code").value("UNAUTHORIZED"));
		}
		jdbc.update("UPDATE ziper.users SET status = 'WITHDRAWN' WHERE id = ?", user.getId());
		mvc.perform(emailLogin("login@example.com", "valid123!")).andExpect(status().isForbidden());
	}

	private static MockHttpServletRequestBuilder emailLogin(String email, String password) {
		return post("/api/v1/auth/email/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password));
	}

	private String pendingSignup(MvcResult result) throws Exception {
		var response = result.getResponse();
		assertThat(response.getStatus()).isEqualTo(200);
		assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
		assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
		JsonNode body = objectMapper.readTree(response.getContentAsString());
		assertThat(body.path("authType").asText()).isEqualTo("SIGNUP_REQUIRED");
		assertThat(body.has("accessToken")).isFalse();
		assertThat(body.has("refreshToken")).isFalse();
		return UUID.fromString(body.path("signupToken").asText()).toString();
	}

	private JsonNode signup(String token) throws Exception {
		MvcResult result = mvc.perform(signupRequest(token, true, true)).andExpect(status().isCreated())
				.andExpect(jsonPath("authType").value("LOGIN")).andExpect(jsonPath("isNewUser").value(true))
				.andExpect(jsonPath("accessToken").isNotEmpty()).andExpect(jsonPath("refreshToken").isNotEmpty())
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE)).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private static MockHttpServletRequestBuilder signupRequest(String token, boolean terms, boolean privacy) {
		return post(OAUTH_SIGNUP).contentType(MediaType.APPLICATION_JSON).content("""
				{"signupToken":"%s","agreements":{"termsOfService":%s,"privacyPolicy":%s,"marketing":false}}
				""".formatted(token, terms, privacy));
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
			} else if ("Bearer kakao-exchanged-token-1".equals(authorization)) {
				body = "{\"id\":500004,\"kakao_account\":{\"email\":\"kakao.code@example.com\"}}";
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
		kakaoUserInfoServer.createContext("/oauth/token", exchange -> {
			String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			int statusCode = form.contains("code=kakao-code-1") ? 200 : 400;
			String body = statusCode == 200
					? "{\"token_type\":\"bearer\",\"access_token\":\"kakao-exchanged-token-1\",\"expires_in\":21599}"
					: "{\"error\":\"invalid_grant\"}";
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
			exchange.sendResponseHeaders(statusCode, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		kakaoUserInfoServer.createContext("/oauth2.0/token", exchange -> {
			String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			String body = form.contains("code=naver-code-1")
					? "{\"access_token\":\"naver-exchanged-token-1\",\"token_type\":\"bearer\",\"expires_in\":\"3600\"}"
					: "{\"error\":\"invalid_request\"}";
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		kakaoUserInfoServer.createContext("/v1/nid/me", exchange -> {
			String authorization = exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION);
			int statusCode = "Bearer naver-exchanged-token-1".equals(authorization) ? 200 : 401;
			String body = statusCode == 200
					? "{\"resultcode\":\"00\",\"message\":\"success\",\"response\":{\"id\":\"naver-code-user-1\",\"email\":\"naver.code@example.com\"}}"
					: "{\"resultcode\":\"024\",\"message\":\"Authentication failed\"}";
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
			exchange.sendResponseHeaders(statusCode, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		kakaoUserInfoServer.createContext("/auth/token", exchange -> {
			String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			int statusCode = form.contains("code=apple-code-1") && form.contains("client_secret=") ? 200 : 400;
			String body;
			try {
				body = statusCode == 200
						? "{\"access_token\":\"apple-access\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"id_token\":\""
								+ createAppleIdToken("apple-code-user-1", "apple.code@privaterelay.appleid.com",
										"com.ject.ziper.web")
								+ "\"}"
						: "{\"error\":\"invalid_grant\"}";
			} catch (Exception exception) {
				statusCode = 500;
				body = "{\"error\":\"server_error\"}";
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
			exchange.sendResponseHeaders(statusCode, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		kakaoUserInfoServer.start();
	}

	private static String createAppleIdToken(String subject, String email, String audience) throws Exception {
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
