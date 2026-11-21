package com.ject.ziper.domain.product.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.domain.product.link.dto.ProductLinkReportRequest;
import com.ject.ziper.domain.product.link.entity.ProductLink;
import com.ject.ziper.domain.product.link.entity.ProductLinkCheckSource;
import com.ject.ziper.domain.product.link.entity.ProductLinkStatus;
import com.ject.ziper.domain.product.link.repository.ProductCardLinkJdbcRepository;
import com.ject.ziper.domain.product.link.repository.ProductCardLinkJdbcRepository.ProductCardLinkSnapshot;
import com.ject.ziper.domain.product.link.repository.ProductLinkReportRepository;
import com.ject.ziper.domain.product.link.repository.ProductLinkRepository;
import com.ject.ziper.domain.product.link.service.ProductLinkService;
import com.ject.ziper.domain.user.entity.User;
import com.ject.ziper.domain.user.repository.UserRepository;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProductLinkStatusIntegrationTest extends PostgresTestSupport {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ProductLinkRepository productLinkRepository;

	@Autowired
	private ProductLinkReportRepository productLinkReportRepository;

	@Autowired
	private ProductCardLinkJdbcRepository productCardLinkJdbcRepository;

	@Autowired
	private ProductLinkService productLinkService;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@BeforeEach
	void cleanUp() {
		jdbc.update("DELETE FROM ziper.product_link_reports");
		jdbc.update("DELETE FROM ziper.product_cards");
		jdbc.update("DELETE FROM ziper.product_links");
		jdbc.update("DELETE FROM ziper.users");
	}

	@Test
	void 인증없이링크상태를조회할때_요청을보내면_401을반환한다() throws Exception {
		mvc.perform(get("/api/v1/products/1/link-status")).andExpect(status().isUnauthorized());
	}

	@Test
	void 소유자가아닌카드나삭제된카드를조회할때_요청을보내면_404를반환한다() throws Exception {
		User owner = createUser("owner@ziper.test", "소유자");
		User other = createUser("other@ziper.test", "타유저");
		ProductLink link = productLinkService.resolveOrCreateLink("https://shop.example/items/1", "musinsa");
		long activeCardId = insertCard(owner.getId(), link.getCanonicalUrl(), link.getId(), false);
		long deletedCardId = insertCard(owner.getId(), "https://shop.example/items/deleted", null, true);

		mvc.perform(get("/api/v1/products/{cardId}/link-status", 999999L).header(HttpHeaders.AUTHORIZATION,
				bearer(owner.getId()))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));

		mvc.perform(get("/api/v1/products/{cardId}/link-status", activeCardId).header(HttpHeaders.AUTHORIZATION,
				bearer(other.getId()))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));

		mvc.perform(get("/api/v1/products/{cardId}/link-status", deletedCardId).header(HttpHeaders.AUTHORIZATION,
				bearer(owner.getId()))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));
	}

	@Test
	void 초기상태의활성상품카드가있을때_링크상태를조회하면_UNKNOWN과null필드를반환한다() throws Exception {
		User user = createUser("init@ziper.test", "초기유저");
		ProductLink link = productLinkService.resolveOrCreateLink("https://shop.example/items/init", "29cm");
		long cardId = insertCard(user.getId(), link.getCanonicalUrl(), link.getId(), false);

		mvc.perform(get("/api/v1/products/{cardId}/link-status", cardId).header(HttpHeaders.AUTHORIZATION,
				bearer(user.getId()))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"))
				.andExpect(jsonPath("$.checkedAt").isEmpty()).andExpect(jsonPath("$.source").isEmpty());
	}

	@Test
	void 보고할수없는상태값으로보고할때_요청을보내면_400을반환한다() throws Exception {
		User user = createUser("invalid@ziper.test", "오류유저");
		ProductLink link = productLinkService.resolveOrCreateLink("https://shop.example/items/invalid", "musinsa");
		long cardId = insertCard(user.getId(), link.getCanonicalUrl(), link.getId(), false);
		Instant observedAt = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.SECONDS);

		for (String invalidStatus : List.of("UNKNOWN", "BROKEN")) {
			String body = objectMapper.writeValueAsString(new ProductLinkReportRequest(invalidStatus, observedAt));
			mvc.perform(post("/api/v1/products/{cardId}/link-status/reports", cardId)
					.header(HttpHeaders.AUTHORIZATION, bearer(user.getId())).contentType(MediaType.APPLICATION_JSON)
					.content(body)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_LINK_STATUS_REPORT"));
		}
	}

	@Test
	void 단일사용자가품절상태를보고할때_보고를전송하면_본인카드만즉시갱신되고공유링크와타인카드는유지된다() throws Exception {
		User reporter = createUser("r1@ziper.test", "보고자일");
		User peer = createUser("r2@ziper.test", "동료유저");
		ProductLink link = productLinkService.resolveOrCreateLink("https://shop.example/items/single", "musinsa");
		long reporterCardId = insertCard(reporter.getId(), link.getCanonicalUrl(), link.getId(), false);
		long peerCardId = insertCard(peer.getId(), link.getCanonicalUrl(), link.getId(), false);
		Instant observedAt = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.SECONDS);

		String body = objectMapper.writeValueAsString(new ProductLinkReportRequest("SOLD_OUT", observedAt));
		mvc.perform(post("/api/v1/products/{cardId}/link-status/reports", reporterCardId)
				.header(HttpHeaders.AUTHORIZATION, bearer(reporter.getId())).contentType(MediaType.APPLICATION_JSON)
				.content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SOLD_OUT"))
				.andExpect(jsonPath("$.checkedAt").value(observedAt.toString()))
				.andExpect(jsonPath("$.source").value("CLIENT"));

		ProductCardLinkSnapshot reporterCard = productCardLinkJdbcRepository
				.findOwnedActiveCard(reporterCardId, reporter.getId()).orElseThrow();
		assertThat(reporterCard.linkStatus()).isEqualTo(ProductLinkStatus.SOLD_OUT);
		assertThat(reporterCard.linkCheckedAt()).isEqualTo(observedAt);
		assertThat(reporterCard.linkCheckSource()).isEqualTo(ProductLinkCheckSource.CLIENT);

		ProductCardLinkSnapshot peerCard = productCardLinkJdbcRepository.findOwnedActiveCard(peerCardId, peer.getId())
				.orElseThrow();
		assertThat(peerCard.linkStatus()).isEqualTo(ProductLinkStatus.UNKNOWN);
		assertThat(peerCard.linkCheckedAt()).isNull();
		assertThat(peerCard.linkCheckSource()).isNull();

		ProductLink reloadedLink = productLinkRepository.findById(link.getId()).orElseThrow();
		assertThat(reloadedLink.getStatus()).isEqualTo(ProductLinkStatus.UNKNOWN);
		assertThat(reloadedLink.getCheckedAt()).isNull();
		assertThat(reloadedLink.getCheckSource()).isNull();
		assertThat(productLinkReportRepository.count()).isEqualTo(1L);
	}

	@Test
	void 서로다른두사용자가24시간내동일상태를보고할때_합의에도달하면_공유링크와구형카드는전파되고최신관측카드는보존된다() throws Exception {
		User user1 = createUser("c1@ziper.test", "합의일");
		User user2 = createUser("c2@ziper.test", "합의이");
		User user3 = createUser("c3@ziper.test", "합의삼");
		User user4 = createUser("c4@ziper.test", "합의사");

		ProductLink link = productLinkService.resolveOrCreateLink("https://shop.example/items/consensus", "zigzag");
		long card1 = insertCard(user1.getId(), link.getCanonicalUrl(), link.getId(), false);
		long card2 = insertCard(user2.getId(), link.getCanonicalUrl(), link.getId(), false);
		long card3 = insertCard(user3.getId(), link.getCanonicalUrl(), link.getId(), false);
		long card4 = insertCard(user4.getId(), link.getCanonicalUrl(), link.getId(), false);

		Instant t1 = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
		Instant t2 = Instant.now().minusSeconds(20).truncatedTo(ChronoUnit.SECONDS);
		Instant t3 = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);

		// 4번째 사용자는 합의 시각보다 더 최신의 개인 관측(AVAILABLE at t3)을 이미 보유
		productCardLinkJdbcRepository.updateCardLinkStatus(card4, user4.getId(), link.getId(),
				ProductLinkStatus.AVAILABLE, t3, ProductLinkCheckSource.CLIENT, t3);

		// 1번째 사용자 보고 (합의 미달)
		mvc.perform(post("/api/v1/products/{cardId}/link-status/reports", card1)
				.header(HttpHeaders.AUTHORIZATION, bearer(user1.getId())).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new ProductLinkReportRequest("SOLD_OUT", t1))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SOLD_OUT"));

		// 2번째 사용자 보고 (2명 합의 도달)
		mvc.perform(post("/api/v1/products/{cardId}/link-status/reports", card2)
				.header(HttpHeaders.AUTHORIZATION, bearer(user2.getId())).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new ProductLinkReportRequest("SOLD_OUT", t2))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SOLD_OUT"))
				.andExpect(jsonPath("$.checkedAt").value(t2.toString()))
				.andExpect(jsonPath("$.source").value("CLIENT"));

		ProductLink reloadedLink = productLinkRepository.findById(link.getId()).orElseThrow();
		assertThat(reloadedLink.getStatus()).isEqualTo(ProductLinkStatus.SOLD_OUT);
		assertThat(reloadedLink.getCheckedAt()).isEqualTo(t2);
		assertThat(reloadedLink.getCheckSource()).isEqualTo(ProductLinkCheckSource.CLIENT);

		ProductCardLinkSnapshot card3Snapshot = productCardLinkJdbcRepository.findOwnedActiveCard(card3, user3.getId())
				.orElseThrow();
		assertThat(card3Snapshot.linkStatus()).isEqualTo(ProductLinkStatus.SOLD_OUT);
		assertThat(card3Snapshot.linkCheckedAt()).isEqualTo(t2);
		assertThat(card3Snapshot.linkCheckSource()).isEqualTo(ProductLinkCheckSource.CLIENT);

		ProductCardLinkSnapshot card4Snapshot = productCardLinkJdbcRepository.findOwnedActiveCard(card4, user4.getId())
				.orElseThrow();
		assertThat(card4Snapshot.linkStatus()).isEqualTo(ProductLinkStatus.AVAILABLE);
		assertThat(card4Snapshot.linkCheckedAt()).isEqualTo(t3);
		assertThat(card4Snapshot.linkCheckSource()).isEqualTo(ProductLinkCheckSource.CLIENT);
	}

	@Test
	void 합의윈도우이전보고만존재할때_다른사용자가동일상태를보고하면_합의전파가발생하지않는다() throws Exception {
		User user1 = createUser("old1@ziper.test", "과거일");
		User user2 = createUser("old2@ziper.test", "과거이");
		User user3 = createUser("old3@ziper.test", "과거삼");

		ProductLink link = productLinkService.resolveOrCreateLink("https://shop.example/items/old-window", "ably");
		long card2 = insertCard(user2.getId(), link.getCanonicalUrl(), link.getId(), false);
		long card3 = insertCard(user3.getId(), link.getCanonicalUrl(), link.getId(), false);

		Instant oldTime = Instant.now().minus(25, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
		jdbc.update(
				"INSERT INTO ziper.product_link_reports(link_id, user_id, status, observed_at, created_at) "
						+ "VALUES (?, ?, 'REMOVED', ?, ?)",
				link.getId(), user1.getId(), Timestamp.from(oldTime), Timestamp.from(oldTime));

		Instant observedAt = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.SECONDS);
		mvc.perform(post("/api/v1/products/{cardId}/link-status/reports", card2)
				.header(HttpHeaders.AUTHORIZATION, bearer(user2.getId())).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new ProductLinkReportRequest("REMOVED", observedAt))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REMOVED"));

		ProductLink reloadedLink = productLinkRepository.findById(link.getId()).orElseThrow();
		assertThat(reloadedLink.getStatus()).isEqualTo(ProductLinkStatus.UNKNOWN);
		ProductCardLinkSnapshot card3Snapshot = productCardLinkJdbcRepository.findOwnedActiveCard(card3, user3.getId())
				.orElseThrow();
		assertThat(card3Snapshot.linkStatus()).isEqualTo(ProductLinkStatus.UNKNOWN);
	}

	@Test
	void 미래시각이나기존보다과거시각으로보고할때_요청을보내면_상태역전없이현재상태를반환한다() throws Exception {
		User user = createUser("stale@ziper.test", "시각유저");
		ProductLink link = productLinkService.resolveOrCreateLink("https://shop.example/items/stale", "musinsa");
		long freshCardId = insertCard(user.getId(), link.getCanonicalUrl(), link.getId(), false);

		Instant futureObservedAt = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS);
		mvc.perform(post("/api/v1/products/{cardId}/link-status/reports", freshCardId)
				.header(HttpHeaders.AUTHORIZATION, bearer(user.getId())).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new ProductLinkReportRequest("REMOVED", futureObservedAt))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"))
				.andExpect(jsonPath("$.checkedAt").isEmpty()).andExpect(jsonPath("$.source").isEmpty());
		assertThat(productLinkReportRepository.count()).isZero();

		Instant existingCheckedAt = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.SECONDS);
		productCardLinkJdbcRepository.updateCardLinkStatus(freshCardId, user.getId(), link.getId(),
				ProductLinkStatus.AVAILABLE, existingCheckedAt, ProductLinkCheckSource.SERVER, existingCheckedAt);

		Instant olderObservedAt = existingCheckedAt.minusSeconds(20);
		mvc.perform(post("/api/v1/products/{cardId}/link-status/reports", freshCardId)
				.header(HttpHeaders.AUTHORIZATION, bearer(user.getId())).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new ProductLinkReportRequest("SOLD_OUT", olderObservedAt))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"))
				.andExpect(jsonPath("$.checkedAt").value(existingCheckedAt.toString()))
				.andExpect(jsonPath("$.source").value("SERVER"));
		assertThat(productLinkReportRepository.count()).isZero();
	}

	@Test
	void 동일사용자가쿨다운내다시보고할때_요청을보내면_429를반환한다() throws Exception {
		User user = createUser("rate@ziper.test", "쿨다운유저");
		ProductLink link = productLinkService.resolveOrCreateLink("https://shop.example/items/ratelimit", "musinsa");
		long cardId = insertCard(user.getId(), link.getCanonicalUrl(), link.getId(), false);
		Instant t1 = Instant.now().minusSeconds(15).truncatedTo(ChronoUnit.SECONDS);
		Instant t2 = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);

		mvc.perform(post("/api/v1/products/{cardId}/link-status/reports", cardId)
				.header(HttpHeaders.AUTHORIZATION, bearer(user.getId())).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new ProductLinkReportRequest("AVAILABLE", t1))))
				.andExpect(status().isOk());

		mvc.perform(post("/api/v1/products/{cardId}/link-status/reports", cardId)
				.header(HttpHeaders.AUTHORIZATION, bearer(user.getId())).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new ProductLinkReportRequest("SOLD_OUT", t2))))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("LINK_STATUS_REPORT_RATE_LIMITED"));
	}

	@Test
	void 링크연결이없는레거시카드로보고할때_요청을보내면_공유링크를생성하고연결한다() throws Exception {
		User user = createUser("legacy@ziper.test", "레거시유저");
		String url = "https://shop.example/items/legacy-no-link";
		long cardId = insertCard(user.getId(), url, null, false);
		Instant observedAt = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);

		mvc.perform(post("/api/v1/products/{cardId}/link-status/reports", cardId)
				.header(HttpHeaders.AUTHORIZATION, bearer(user.getId())).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(new ProductLinkReportRequest("AVAILABLE", observedAt))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"));

		ProductLink created = productLinkRepository.findByCanonicalUrl(url).orElseThrow();
		ProductCardLinkSnapshot snapshot = productCardLinkJdbcRepository.findOwnedActiveCard(cardId, user.getId())
				.orElseThrow();
		assertThat(snapshot.linkId()).isEqualTo(created.getId());
		assertThat(snapshot.linkStatus()).isEqualTo(ProductLinkStatus.AVAILABLE);
	}

	@Test
	void 서버링크점검결과를적용할때_합의없이호출하면_공유링크와구형카드에즉시전파되고UNKNOWN은무시된다() {
		User user1 = createUser("s1@ziper.test", "서버일");
		User user2 = createUser("s2@ziper.test", "서버이");
		ProductLink link = productLinkService.resolveOrCreateLink("https://shop.example/items/server-check",
				"wconcept");
		long card1 = insertCard(user1.getId(), link.getCanonicalUrl(), link.getId(), false);
		long card2 = insertCard(user2.getId(), link.getCanonicalUrl(), link.getId(), false);

		Instant serverTime = Instant.now().minusSeconds(20).truncatedTo(ChronoUnit.SECONDS);
		Instant newerClientTime = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
		productCardLinkJdbcRepository.updateCardLinkStatus(card2, user2.getId(), link.getId(),
				ProductLinkStatus.AVAILABLE, newerClientTime, ProductLinkCheckSource.CLIENT, newerClientTime);

		productLinkService.applyServerCheck(link.getId(), ProductLinkStatus.REMOVED, serverTime);

		ProductLink reloaded = productLinkRepository.findById(link.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(ProductLinkStatus.REMOVED);
		assertThat(reloaded.getCheckedAt()).isEqualTo(serverTime);
		assertThat(reloaded.getCheckSource()).isEqualTo(ProductLinkCheckSource.SERVER);

		ProductCardLinkSnapshot card1Snapshot = productCardLinkJdbcRepository.findOwnedActiveCard(card1, user1.getId())
				.orElseThrow();
		assertThat(card1Snapshot.linkStatus()).isEqualTo(ProductLinkStatus.REMOVED);
		assertThat(card1Snapshot.linkCheckedAt()).isEqualTo(serverTime);
		assertThat(card1Snapshot.linkCheckSource()).isEqualTo(ProductLinkCheckSource.SERVER);

		ProductCardLinkSnapshot card2Snapshot = productCardLinkJdbcRepository.findOwnedActiveCard(card2, user2.getId())
				.orElseThrow();
		assertThat(card2Snapshot.linkStatus()).isEqualTo(ProductLinkStatus.AVAILABLE);
		assertThat(card2Snapshot.linkCheckedAt()).isEqualTo(newerClientTime);
		assertThat(card2Snapshot.linkCheckSource()).isEqualTo(ProductLinkCheckSource.CLIENT);

		// UNKNOWN 서버 점검 결과는 기존 상태를 덮어쓰지 않음
		productLinkService.applyServerCheck(link.getId(), ProductLinkStatus.UNKNOWN, Instant.now());
		ProductLink afterUnknown = productLinkRepository.findById(link.getId()).orElseThrow();
		assertThat(afterUnknown.getStatus()).isEqualTo(ProductLinkStatus.REMOVED);
		assertThat(afterUnknown.getCheckedAt()).isEqualTo(serverTime);
	}

	@Test
	void 동일URL로링크생성을동시에요청할때_자문잠금으로호출하면_단일링크행만생성된다() throws Exception {
		String url = "https://shop.example/items/concurrent-link";
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			List<Callable<Long>> tasks = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				tasks.add(() -> productLinkService.resolveOrCreateLink(url, "musinsa").getId());
			}
			List<Future<Long>> results = executor.invokeAll(tasks);
			Long firstId = results.getFirst().get();
			for (Future<Long> future : results) {
				assertThat(future.get()).isEqualTo(firstId);
			}
			assertThat(productLinkRepository.findByCanonicalUrl(url)).isPresent();
			assertThat(productLinkRepository.count()).isEqualTo(1L);
		} finally {
			executor.shutdownNow();
		}
	}

	private User createUser(String email, String nickname) {
		return userRepository.save(User.createEmailUser(email, "hashed-password", nickname, false, Instant.now()));
	}

	private long insertCard(Long userId, String productUrl, Long linkId, boolean deleted) {
		Instant deletedAt = deleted ? Instant.now() : null;
		return jdbc.queryForObject(
				"INSERT INTO ziper.product_cards(user_id, product_url, product_title, link_id, is_deleted, deleted_at) "
						+ "VALUES (?, ?, '테스트 상품', ?, ?, ?) RETURNING id",
				Long.class, userId, productUrl, linkId, deleted, deletedAt == null ? null : Timestamp.from(deletedAt));
	}

	private String bearer(Long userId) {
		return "Bearer " + jwtTokenProvider.issueAccessToken(userId);
	}
}
