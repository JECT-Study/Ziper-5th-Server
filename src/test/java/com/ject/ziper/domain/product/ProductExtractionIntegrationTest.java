package com.ject.ziper.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.domain.product.extraction.ExtractionRuleRegistry;
import com.ject.ziper.domain.user.entity.User;
import com.ject.ziper.domain.user.repository.UserRepository;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;
import com.sun.net.httpserver.HttpServer;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProductExtractionIntegrationTest extends PostgresTestSupport {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private User user;
	private String accessToken;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM ziper.product_cards");
		userRepository.deleteAll();
		user = userRepository.save(
				User.createEmailUser("product-tester@example.com", "{bcrypt}hash", "상품테스터", false, Instant.now()));
		accessToken = jwtTokenProvider.issueAccessToken(user.getId());
	}

	@Test
	void 미인증요청이들어오면_추출규칙과URL확인을호출하면_모두401로거부한다() throws Exception {
		mvc.perform(get("/api/v1/products/extraction-rules")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("code").value("UNAUTHORIZED"));

		mvc.perform(post("/api/v1/products/url-check").contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"https://www.musinsa.com/products/1234567\"}")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("code").value("UNAUTHORIZED"));
	}

	@Test
	void 인증된사용자가추출규칙을조회하면_ETag와조건부요청을수행하면_200응답과304캐시응답을반환한다() throws Exception {
		var response = mvc
				.perform(get("/api/v1/products/extraction-rules").header(HttpHeaders.AUTHORIZATION,
						"Bearer " + accessToken))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=300, must-revalidate"))
				.andExpect(header().exists(HttpHeaders.ETAG))
				.andExpect(jsonPath("version").value(ExtractionRuleRegistry.CURRENT_VERSION))
				.andExpect(jsonPath("shops.length()").value(3)).andExpect(jsonPath("shops[0].shop").value("무신사"))
				.andExpect(jsonPath("shops[0].imageHosts[0]").value("image.msscdn.net"))
				.andExpect(jsonPath("shops[0].imageStrategy").value("SERVER_MIRROR"))
				.andExpect(jsonPath("shops[1].shop").value("에이블리"))
				.andExpect(jsonPath("shops[1].imageHosts[0]").value("d3ha2047wt6x28.cloudfront.net"))
				.andExpect(jsonPath("shops[2].shop").value("지그재그"))
				.andExpect(jsonPath("shops[2].imageHosts[0]").value("cf.product-image.s.zigzag.kr"))
				.andExpect(jsonPath("generic.fields.title[0]").value("meta[property='og:title']@content"))
				.andExpect(jsonPath("generic.fields.image[0]").value("meta[property='og:image']@content")).andReturn()
				.getResponse();

		String etag = response.getHeader(HttpHeaders.ETAG);
		assertThat(etag).isNotNull().startsWith("\"").endsWith("\"");

		mvc.perform(get("/api/v1/products/extraction-rules").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.header(HttpHeaders.IF_NONE_MATCH, etag)).andExpect(status().isNotModified())
				.andExpect(header().string(HttpHeaders.ETAG, etag))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=300, must-revalidate"))
				.andExpect(content().string(""));

		mvc.perform(get("/api/v1/products/extraction-rules").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.header(HttpHeaders.IF_NONE_MATCH, "\"outdated-etag\"")).andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ETAG, etag))
				.andExpect(jsonPath("version").value(ExtractionRuleRegistry.CURRENT_VERSION));
	}

	@Test
	void 허용된Origin에서사전요청을보내면_IfNoneMatch와ETag헤더를확인하면_CORS정책이허용하고노출한다() throws Exception {
		mvc.perform(options("/api/v1/products/extraction-rules").header("Origin", "http://localhost:3000")
				.header("Access-Control-Request-Method", "GET")
				.header("Access-Control-Request-Headers", "Authorization, If-None-Match")).andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));

		var getResult = mvc
				.perform(get("/api/v1/products/extraction-rules").header("Origin", "http://localhost:3000")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn().getResponse();
		assertThat(getResult.getHeader("Access-Control-Expose-Headers")).contains("ETag");
	}

	@Test
	void 이미저장된상품과미저장상품이있을때_URL확인을요청하면_정규화결과와본인기존카드ID만반환한다() throws Exception {
		Long savedCardId = jdbcTemplate.queryForObject(
				"INSERT INTO ziper.product_cards (user_id, product_url, product_title, price) "
						+ "VALUES (?, ?, ?, ?) RETURNING id",
				Long.class, user.getId(), "https://www.musinsa.com/products/1234567", "무신사 티셔츠", 29000L);

		mvc.perform(post("/api/v1/products/url-check").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"https://m.musinsa.com/app/goods/1234567/0?utm_source=kakao#reviews\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("canonicalUrl").value("https://www.musinsa.com/products/1234567"))
				.andExpect(jsonPath("shop").value("무신사")).andExpect(jsonPath("supported").value(true))
				.andExpect(jsonPath("existingCardId").value(savedCardId));

		User otherUser = userRepository
				.save(User.createEmailUser("other-tester@example.com", "{bcrypt}hash", "다른테스터", false, Instant.now()));
		String otherToken = jwtTokenProvider.issueAccessToken(otherUser.getId());

		mvc.perform(post("/api/v1/products/url-check").header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"https://m.musinsa.com/products/1234567?fbclid=xyz\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("canonicalUrl").value("https://www.musinsa.com/products/1234567"))
				.andExpect(jsonPath("shop").value("무신사")).andExpect(jsonPath("supported").value(true))
				.andExpect(jsonPath("existingCardId").doesNotExist());

		mvc.perform(post("/api/v1/products/url-check").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"https://unknown-shop.example.com/item/10?b=2&utm_source=ig&a=1#frag\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("canonicalUrl").value("https://unknown-shop.example.com/item/10?a=1&b=2"))
				.andExpect(jsonPath("shop").doesNotExist()).andExpect(jsonPath("supported").value(false))
				.andExpect(jsonPath("existingCardId").doesNotExist());
	}

	@Test
	void 추출규칙응답의패턴과같은입력이주어지면_URL확인을요청하면_클라이언트패턴판정과서버판정이일치한다() throws Exception {
		String rules = mvc
				.perform(get("/api/v1/products/extraction-rules").header(HttpHeaders.AUTHORIZATION,
						"Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		JsonNode shops = new ObjectMapper().readTree(rules).get("shops");
		String[] paths = {"/products/1234567", "/products/1234567/", "/products/1234567a", "/products/a1234567",
				"/shop/products/1234567", "/app/goods/987/0", "/goods/123", "/goods/12x", "/catalog/products/777",
				"/catalog/products/777x", "/x/catalog/products/777", "/categories/001"};

		for (JsonNode shop : shops) {
			Pattern clientPattern = Pattern.compile(shop.get("canonical").get("pattern").asText());
			String host = shop.get("hosts").get(1).asText();
			for (String path : paths) {
				boolean clientMatches = clientPattern.matcher(path).find();
				var result = mvc.perform(post("/api/v1/products/url-check")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://" + host + path + "\"}"))
						.andExpect(status().isOk()).andExpect(jsonPath("supported").value(clientMatches));
				if (clientMatches) {
					result.andExpect(jsonPath("shop").value(shop.get("shop").asText()));
				} else {
					result.andExpect(jsonPath("shop").doesNotExist());
				}
			}
		}
	}

	@Test
	void 인용된ETag내부에별표가있는IfNoneMatch가주어지면_추출규칙을조회하면_304가아닌200을반환한다() throws Exception {
		String etag = mvc.perform(
				get("/api/v1/products/extraction-rules").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andReturn().getResponse().getHeader(HttpHeaders.ETAG);

		mvc.perform(get("/api/v1/products/extraction-rules").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.header(HttpHeaders.IF_NONE_MATCH, "\"a,*\"")).andExpect(status().isOk());
		mvc.perform(get("/api/v1/products/extraction-rules").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.header(HttpHeaders.IF_NONE_MATCH, "\"a,b\", W/" + etag)).andExpect(status().isNotModified());
	}

	@Test
	void 지원몰호스트의비기본포트와인코딩된ID가주어지면_URL확인을요청하면_수기입력흐름과디코딩정규화로응답한다() throws Exception {
		mvc.perform(post("/api/v1/products/url-check").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"https://www.musinsa.com:8443/products/1234567\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("supported").value(false)).andExpect(jsonPath("shop").doesNotExist());
		mvc.perform(post("/api/v1/products/url-check").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"https://www.musinsa.com/categories/001\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("supported").value(false)).andExpect(jsonPath("shop").doesNotExist());
		mvc.perform(post("/api/v1/products/url-check").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"https://www.musinsa.com/products/%31234567\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("supported").value(true))
				.andExpect(jsonPath("canonicalUrl").value("https://www.musinsa.com/products/1234567"));
	}

	@Test
	void 잘못된URL또는빈요청이주어지면_URL확인을요청하면_400오류를반환한다() throws Exception {
		mvc.perform(post("/api/v1/products/url-check").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"javascript:alert(1)\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_PRODUCT_URL"));

		mvc.perform(post("/api/v1/products/url-check").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"file:///etc/passwd\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_PRODUCT_URL"));

		mvc.perform(post("/api/v1/products/url-check").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"   \"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
	}

	@Test
	void 로컬HTTP서버주소가전달되어도_URL확인을요청하면_외부HTTP요청을전혀보내지않는다() throws Exception {
		AtomicInteger hitCount = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			hitCount.incrementAndGet();
			byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			int port = server.getAddress().getPort();
			String targetUrl = "http://127.0.0.1:" + port + "/products/999?utm_source=test&id=7#section";

			mvc.perform(post("/api/v1/products/url-check").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
					.contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + targetUrl + "\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("canonicalUrl").value("http://127.0.0.1:" + port + "/products/999?id=7"))
					.andExpect(jsonPath("supported").value(false));

			assertThat(hitCount.get()).isZero();
		} finally {
			server.stop(0);
		}
	}
}
