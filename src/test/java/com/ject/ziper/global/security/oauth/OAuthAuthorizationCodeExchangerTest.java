package com.ject.ziper.global.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.security.SecurityProperties;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

class OAuthAuthorizationCodeExchangerTest {
	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
	}

	@Test
	void 유효한Apple인가코드와개인키_토큰을교환하면_ES256클라이언트시크릿으로서명해ID토큰을반환한다() throws Exception {
		KeyPair ecPair = generateEcKeyPair();
		String pem = toPem((ECPrivateKey) ecPair.getPrivate());
		AtomicReference<Map<String, String>> capturedForm = new AtomicReference<>();

		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/auth/token", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			capturedForm.set(parseForm(body));
			byte[] response = "{\"access_token\":\"apple-access\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"id_token\":\"apple.id.jwt\"}"
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		int port = server.getAddress().getPort();

		SecurityProperties properties = createProperties("http://127.0.0.1:" + port + "/auth/token", pem,
				"http://127.0.0.1:" + port + "/naver/token", "naver-secret",
				"http://127.0.0.1:" + port + "/kakao/token", "kakao-secret");
		Clock fixedClock = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
		var exchanger = new OAuthAuthorizationCodeExchanger(RestClient.builder(), properties,
				new AppleClientSecretGenerator(fixedClock));

		String idToken = exchanger.exchange(OAuthProvider.APPLE, "apple-auth-code", "https://app.ziper.dev/cb",
				"state-1");

		assertThat(idToken).isEqualTo("apple.id.jwt");
		Map<String, String> form = capturedForm.get();
		assertThat(form).containsEntry("grant_type", "authorization_code").containsEntry("code", "apple-auth-code")
				.containsEntry("client_id", "com.ject.ziper.web")
				.containsEntry("redirect_uri", "https://app.ziper.dev/cb");

		SignedJWT clientSecretJwt = SignedJWT.parse(form.get("client_secret"));
		assertThat(clientSecretJwt.verify(new ECDSAVerifier((ECPublicKey) ecPair.getPublic()))).isTrue();
		assertThat(clientSecretJwt.getHeader().getKeyID()).isEqualTo("KEY1234567");
		assertThat(clientSecretJwt.getJWTClaimsSet().getIssuer()).isEqualTo("TEAM123456");
		assertThat(clientSecretJwt.getJWTClaimsSet().getSubject()).isEqualTo("com.ject.ziper.web");
		assertThat(clientSecretJwt.getJWTClaimsSet().getAudience()).containsExactly("https://appleid.apple.com");
	}

	@Test
	void 유효한Naver인가코드와오류응답_토큰을교환하면_정상토큰은추출하고오류JSON은UNAUTHORIZED를던진다() throws Exception {
		AtomicReference<Map<String, String>> capturedForm = new AtomicReference<>();

		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/naver/token", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			Map<String, String> form = parseForm(body);
			capturedForm.set(form);
			String json = "valid-naver-code".equals(form.get("code"))
					? "{\"access_token\":\"naver-access-123\",\"refresh_token\":\"naver-refresh\",\"token_type\":\"bearer\",\"expires_in\":\"3600\"}"
					: "{\"error\":\"invalid_request\",\"error_description\":\"invalid code\"}";
			byte[] response = json.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		int port = server.getAddress().getPort();

		SecurityProperties properties = createProperties("http://127.0.0.1:" + port + "/auth/token", "",
				"http://127.0.0.1:" + port + "/naver/token", "naver-secret",
				"http://127.0.0.1:" + port + "/kakao/token", "kakao-secret");
		var exchanger = new OAuthAuthorizationCodeExchanger(RestClient.builder(), properties,
				new AppleClientSecretGenerator());

		String accessToken = exchanger.exchange(OAuthProvider.NAVER, "valid-naver-code", null, "naver-state-1");

		assertThat(accessToken).isEqualTo("naver-access-123");
		assertThat(capturedForm.get()).containsEntry("grant_type", "authorization_code")
				.containsEntry("client_id", "naver-id").containsEntry("client_secret", "naver-secret")
				.containsEntry("code", "valid-naver-code").containsEntry("state", "naver-state-1")
				.doesNotContainKey("redirect_uri");

		assertThatThrownBy(() -> exchanger.exchange(OAuthProvider.NAVER, "bad-naver-code", null, "naver-state-1"))
				.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
	}

	@Test
	void 유효한Kakao인가코드와시크릿없음_토큰을교환하면_요청파라미터에맞게액세스토큰을반환하고4xx는거부한다() throws Exception {
		AtomicReference<Map<String, String>> capturedForm = new AtomicReference<>();

		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/kakao/token", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			Map<String, String> form = parseForm(body);
			capturedForm.set(form);
			int status = "valid-kakao-code".equals(form.get("code")) ? 200 : 400;
			String json = status == 200
					? "{\"token_type\":\"bearer\",\"access_token\":\"kakao-access-456\",\"expires_in\":21599}"
					: "{\"error\":\"invalid_grant\",\"error_description\":\"Bad authorization code\"}";
			byte[] response = json.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
			exchange.sendResponseHeaders(status, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		int port = server.getAddress().getPort();

		SecurityProperties propertiesWithSecret = createProperties("http://127.0.0.1:" + port + "/auth/token", "",
				"http://127.0.0.1:" + port + "/naver/token", "naver-secret",
				"http://127.0.0.1:" + port + "/kakao/token", "kakao-secret");
		var exchangerWithSecret = new OAuthAuthorizationCodeExchanger(RestClient.builder(), propertiesWithSecret,
				new AppleClientSecretGenerator());

		String tokenWithSecret = exchangerWithSecret.exchange(OAuthProvider.KAKAO, "valid-kakao-code",
				"https://app.ziper.dev/callback/kakao", null);
		assertThat(tokenWithSecret).isEqualTo("kakao-access-456");
		assertThat(capturedForm.get()).containsEntry("client_id", "kakao-id")
				.containsEntry("client_secret", "kakao-secret")
				.containsEntry("redirect_uri", "https://app.ziper.dev/callback/kakao");

		SecurityProperties propertiesWithoutSecret = createProperties("http://127.0.0.1:" + port + "/auth/token", "",
				"http://127.0.0.1:" + port + "/naver/token", "naver-secret",
				"http://127.0.0.1:" + port + "/kakao/token", "");
		var exchangerWithoutSecret = new OAuthAuthorizationCodeExchanger(RestClient.builder(), propertiesWithoutSecret,
				new AppleClientSecretGenerator());

		String tokenWithoutSecret = exchangerWithoutSecret.exchange(OAuthProvider.KAKAO, "valid-kakao-code",
				"https://app.ziper.dev/callback/kakao", null);
		assertThat(tokenWithoutSecret).isEqualTo("kakao-access-456");
		assertThat(capturedForm.get()).containsEntry("client_id", "kakao-id").doesNotContainKey("client_secret");

		assertThatThrownBy(() -> exchangerWithSecret.exchange(OAuthProvider.KAKAO, "expired-code",
				"https://app.ziper.dev/callback/kakao", null)).isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
		assertThatThrownBy(() -> exchangerWithSecret.exchange(OAuthProvider.KAKAO, " ", null, null))
				.isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
	}

	private SecurityProperties createProperties(String appleTokenUri, String applePrivateKey, String naverTokenUri,
			String naverSecret, String kakaoTokenUri, String kakaoSecret) {
		String jwtSecret = Base64.getEncoder().encodeToString(new byte[32]);
		return new SecurityProperties(jwtSecret, "ziper-server", "ziper-api", List.of("https://app.ziper.dev"), 3600L,
				1209600L, "https://appleid.apple.com", "https://appleid.apple.com/auth/keys",
				List.of("com.ject.ziper", "com.ject.ziper.web"), appleTokenUri, "com.ject.ziper.web", "TEAM123456",
				"KEY1234567", applePrivateKey, "https://openapi.naver.com/v1/nid/me", naverTokenUri, "naver-id",
				naverSecret, "https://kapi.kakao.com/v2/user/me", kakaoTokenUri, "kakao-id", kakaoSecret);
	}

	private KeyPair generateEcKeyPair() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
		generator.initialize(new ECGenParameterSpec("secp256r1"));
		return generator.generateKeyPair();
	}

	private String toPem(ECPrivateKey privateKey) {
		String encoded = Base64.getEncoder().encodeToString(privateKey.getEncoded());
		return "-----BEGIN PRIVATE KEY-----\\n" + encoded + "\\n-----END PRIVATE KEY-----";
	}

	private Map<String, String> parseForm(String body) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String pair : body.split("&")) {
			if (pair.isEmpty()) {
				continue;
			}
			int idx = pair.indexOf('=');
			if (idx < 0) {
				result.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
			} else {
				result.put(URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8),
						URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
			}
		}
		return result;
	}
}
