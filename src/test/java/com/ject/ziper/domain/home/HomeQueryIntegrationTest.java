package com.ject.ziper.domain.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.domain.file.entity.FileAsset;
import com.ject.ziper.domain.file.repository.FileAssetRepository;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class HomeQueryIntegrationTest extends PostgresTestSupport {
	private static final String HOME_PATH = "/api/v1/home";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private FileAssetRepository fileAssetRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	private Long userId;
	private Long otherUserId;
	private String accessToken;

	@BeforeEach
	void setUp() {
		clearDatabase();
		userId = insertUser("home-owner@example.com", "홈오너");
		otherUserId = insertUser("home-other@example.com", "다른유저");
		accessToken = jwtTokenProvider.issueAccessToken(userId);
	}

	@AfterEach
	void tearDown() {
		clearDatabase();
	}

	@Test
	void 인증토큰이없는요청_홈요약을조회하면_401을반환한다() throws Exception {
		mvc.perform(get(HOME_PATH)).andExpect(status().isUnauthorized());
	}

	@Test
	void 데이터가없는신규사용자_홈요약을조회하면_빈목록과0건을반환한다() throws Exception {
		mvc.perform(get(HOME_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.rediscoveryCount").value(0))
				.andExpect(jsonPath("$.boards").isArray()).andExpect(jsonPath("$.boards.length()").value(0))
				.andExpect(jsonPath("$.recentProducts").isArray())
				.andExpect(jsonPath("$.recentProducts.length()").value(0));
	}

	@Test
	void 재발견대상과제외조건상품이혼재된상태_홈요약을조회하면_유효한PLANNED기한도래상품만재발견개수로집계한다() throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		Instant pastOneDay = now.minus(1, ChronoUnit.DAYS);
		Instant pastOneMinute = now.minus(1, ChronoUnit.MINUTES);
		Instant futureOneDay = now.plus(1, ChronoUnit.DAYS);

		// Included: PLANNED, not deleted, AVAILABLE, next_rediscovery_at <= now
		insertCard(userId, "https://www.musinsa.com/products/1001", "무신사", "포함상품1", 29000L,
				"https://image.msscdn.net/1001.jpg", null, null, "NONE", "PLANNED", "AVAILABLE", pastOneDay, "CLIENT",
				pastOneDay, false, null, pastOneDay);
		// Included: PLANNED, not deleted, UNKNOWN, next_rediscovery_at <= now
		insertCard(userId, "https://www.musinsa.com/products/1002", "무신사", "포함상품2", 31000L, null, null, "TOP", "NONE",
				"PLANNED", "UNKNOWN", null, null, pastOneMinute, false, null, pastOneMinute);

		// Excluded: REMOVED
		insertCard(userId, "https://www.musinsa.com/products/1003", "무신사", "제외삭제링크", 32000L, null, null, "TOP", "NONE",
				"PLANNED", "REMOVED", pastOneDay, "CLIENT", pastOneDay, false, null, pastOneDay);
		// Excluded: SOLD_OUT
		insertCard(userId, "https://www.musinsa.com/products/1004", "무신사", "제외품절링크", 33000L, null, null, "TOP", "NONE",
				"PLANNED", "SOLD_OUT", pastOneDay, "CLIENT", pastOneDay, false, null, pastOneDay);
		// Excluded: PURCHASED
		insertCard(userId, "https://www.musinsa.com/products/1005", "무신사", "제외구매완료", 34000L, null, null, "TOP", "NONE",
				"PURCHASED", "AVAILABLE", pastOneDay, "SERVER", pastOneDay, false, null, pastOneDay);
		// Excluded: soft-deleted
		insertCard(userId, "https://www.musinsa.com/products/1006", "무신사", "제외휴지통", 35000L, null, null, "TOP", "NONE",
				"PLANNED", "AVAILABLE", pastOneDay, "SERVER", pastOneDay, true, now, pastOneDay);
		// Excluded: future next_rediscovery_at
		insertCard(userId, "https://www.musinsa.com/products/1007", "무신사", "제외미래기한", 36000L, null, null, "TOP", "NONE",
				"PLANNED", "AVAILABLE", pastOneDay, "SERVER", futureOneDay, false, null, now);
		// Excluded: other user's eligible card
		insertCard(otherUserId, "https://www.musinsa.com/products/1008", "무신사", "타인상품", 37000L, null, null, "TOP",
				"NONE", "PLANNED", "AVAILABLE", pastOneDay, "SERVER", pastOneDay, false, null, pastOneDay);

		mvc.perform(get(HOME_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.rediscoveryCount").value(2));
	}

	@Test
	void 기본보드와커스텀보드및삭제보드가존재하는상태_홈요약을조회하면_순수최신생성순으로정렬하고보드별활성개수와최대3개썸네일을반환한다() throws Exception {
		Instant baseTime = Instant.parse("2026-11-01T00:00:00Z");

		Long defaultBoardId = insertBoard(userId, "기본 보드", false, false, null, baseTime);
		Long springBoardId = insertBoard(userId, "봄 코디", true, false, null, baseTime.plusSeconds(10));
		Long tieBoardOlderId = insertBoard(userId, "동시생성A", true, false, null, baseTime.plusSeconds(20));
		Long tieBoardNewerId = insertBoard(userId, "동시생성B", true, false, null, baseTime.plusSeconds(20));
		Long deletedBoardId = insertBoard(userId, "삭제된 보드", true, true, baseTime.plusSeconds(30),
				baseTime.plusSeconds(30));
		Long otherUserBoardId = insertBoard(otherUserId, "타인 보드", true, false, null, baseTime.plusSeconds(40));

		FileAsset storedAsset = fileAssetRepository.save(FileAsset.create("thumbnails/2026/11/home-preview-1.webp",
				userId, "preview.webp", "image/webp", 1024L, baseTime));

		Instant futureRediscovery = Instant.now().plus(14, ChronoUnit.DAYS);
		Long cardOldest = insertCard(userId, "https://www.musinsa.com/products/2001", "무신사", "가장오래된상품", 10000L,
				"https://image.msscdn.net/2001.jpg", null, null, "PENDING", "PLANNED", "UNKNOWN", null, null,
				futureRediscovery, false, null, baseTime.plusSeconds(100));
		Long cardFallback = insertCard(userId, "https://www.musinsa.com/products/2002", "무신사", "아이콘상품", 20000L, null,
				null, "TOP", "NONE", "PLANNED", "UNKNOWN", null, null, futureRediscovery, false, null,
				baseTime.plusSeconds(101));
		Long cardExternalUrl = insertCard(userId, "https://www.musinsa.com/products/2003", "무신사", "외부이미지상품", 30000L,
				"https://image.msscdn.net/2003.jpg", null, null, "PENDING", "PLANNED", "UNKNOWN", null, null,
				futureRediscovery, false, null, baseTime.plusSeconds(102));
		Long cardMirrored = insertCard(userId, "https://www.musinsa.com/products/2004", "무신사", "미러링완료상품", 40000L,
				"https://image.msscdn.net/2004.jpg", storedAsset.getId(), "PANTS", "READY", "PLANNED", "AVAILABLE",
				baseTime.plusSeconds(103), "SERVER", futureRediscovery, false, null, baseTime.plusSeconds(103));
		Long cardPurchased = insertCard(userId, "https://www.musinsa.com/products/2005", "무신사", "구매완료상품", 50000L,
				"https://image.msscdn.net/2005.jpg", null, null, "NONE", "PURCHASED", "AVAILABLE", null, null,
				futureRediscovery, false, null, baseTime.plusSeconds(104));
		Long cardDeleted = insertCard(userId, "https://www.musinsa.com/products/2006", "무신사", "삭제상품", 60000L,
				"https://image.msscdn.net/2006.jpg", null, null, "NONE", "PLANNED", "AVAILABLE", null, null,
				futureRediscovery, true, baseTime.plusSeconds(105), baseTime.plusSeconds(105));
		Long otherCard = insertCard(otherUserId, "https://www.musinsa.com/products/2007", "무신사", "타인상품", 70000L,
				"https://image.msscdn.net/2007.jpg", null, null, "NONE", "PLANNED", "AVAILABLE", null, null,
				futureRediscovery, false, null, baseTime.plusSeconds(106));

		linkCardToBoard(springBoardId, cardOldest, baseTime.plusSeconds(200));
		linkCardToBoard(springBoardId, cardFallback, baseTime.plusSeconds(201));
		linkCardToBoard(springBoardId, cardExternalUrl, baseTime.plusSeconds(202));
		linkCardToBoard(springBoardId, cardMirrored, baseTime.plusSeconds(203));
		linkCardToBoard(springBoardId, cardPurchased, baseTime.plusSeconds(204));
		linkCardToBoard(springBoardId, cardDeleted, baseTime.plusSeconds(205));
		linkCardToBoard(deletedBoardId, cardMirrored, baseTime.plusSeconds(206));
		linkCardToBoard(otherUserBoardId, otherCard, baseTime.plusSeconds(207));

		String body = mvc.perform(get(HOME_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		JsonNode root = objectMapper.readTree(body);
		JsonNode boards = root.path("boards");

		// Pure latest creation order (created_at DESC, id DESC) — '기본 보드' is NOT pinned
		// to top
		assertThat(boards.size()).isEqualTo(4);
		assertThat(boards.get(0).path("boardId").asLong()).isEqualTo(tieBoardNewerId);
		assertThat(boards.get(0).path("name").asText()).isEqualTo("동시생성B");
		assertThat(boards.get(0).path("modifiable").asBoolean()).isTrue();
		assertThat(boards.get(0).path("productCount").asLong()).isZero();
		assertThat(boards.get(0).path("previewImages").size()).isZero();

		assertThat(boards.get(1).path("boardId").asLong()).isEqualTo(tieBoardOlderId);
		assertThat(boards.get(1).path("name").asText()).isEqualTo("동시생성A");

		JsonNode springBoard = boards.get(2);
		assertThat(springBoard.path("boardId").asLong()).isEqualTo(springBoardId);
		assertThat(springBoard.path("name").asText()).isEqualTo("봄 코디");
		assertThat(springBoard.path("modifiable").asBoolean()).isTrue();
		assertThat(springBoard.path("productCount").asLong()).isEqualTo(4L);
		assertThat(springBoard.path("createdAt").asText()).isEqualTo(baseTime.plusSeconds(10).toString());

		JsonNode previews = springBoard.path("previewImages");
		assertThat(previews.size()).isEqualTo(3);

		// Preview #1: mirrored file signed URL takes priority over original image_url
		assertThat(previews.get(0).path("cardId").asLong()).isEqualTo(cardMirrored);
		assertThat(previews.get(0).path("imageUrl").asText())
				.startsWith("https://storage.local/signed/thumbnails/2026/11/home-preview-1.webp");
		assertThat(previews.get(0).path("fallbackIcon").asText()).isEqualTo("PANTS");

		// Preview #2: external image_url fallback when image_file_id is null
		assertThat(previews.get(1).path("cardId").asLong()).isEqualTo(cardExternalUrl);
		assertThat(previews.get(1).path("imageUrl").asText()).isEqualTo("https://image.msscdn.net/2003.jpg");
		assertThat(previews.get(1).path("fallbackIcon").isNull()).isTrue();

		// Preview #3: fallback icon only when both image_file_id and image_url are null
		assertThat(previews.get(2).path("cardId").asLong()).isEqualTo(cardFallback);
		assertThat(previews.get(2).path("imageUrl").isNull()).isTrue();
		assertThat(previews.get(2).path("fallbackIcon").asText()).isEqualTo("TOP");

		// Default board is last because it was created first
		JsonNode defaultBoard = boards.get(3);
		assertThat(defaultBoard.path("boardId").asLong()).isEqualTo(defaultBoardId);
		assertThat(defaultBoard.path("name").asText()).isEqualTo("기본 보드");
		assertThat(defaultBoard.path("modifiable").asBoolean()).isFalse();
		assertThat(defaultBoard.path("productCount").asLong()).isZero();
		assertThat(defaultBoard.path("previewImages").size()).isZero();
	}

	@Test
	void 여러상품카드가저장된상태_최근저장상품을조회하면_최신순으로RevB필드를포함해제한개수만큼반환한다() throws Exception {
		Instant baseTime = Instant.parse("2026-11-10T10:00:00Z");
		Instant futureRediscovery = Instant.now().plus(14, ChronoUnit.DAYS);
		Instant checkedAt = Instant.parse("2026-11-10T11:30:00Z");

		FileAsset storedAsset = fileAssetRepository.save(FileAsset.create("thumbnails/2026/11/recent-1.webp", userId,
				"recent.webp", "image/webp", 2048L, baseTime));

		insertCard(userId, "https://www.musinsa.com/products/3001", "무신사", "오래된상품", 15000L,
				"https://image.msscdn.net/3001.jpg", null, null, "PENDING", "PLANNED", "UNKNOWN", null, null,
				futureRediscovery, false, null, baseTime);
		Long secondNewestId = insertCard(userId, "https://www.zigzag.kr/catalog/products/3002", null, "대체아이콘상품", 25000L,
				null, null, "ACCESSORY", "NONE", "PLANNED", "UNKNOWN", null, null, futureRediscovery, false, null,
				baseTime.plusSeconds(10));
		Long newestId = insertCard(userId, "https://www.musinsa.com/products/3003", "무신사", "최신상품", 39000L,
				"https://image.msscdn.net/3003.jpg", storedAsset.getId(), null, "READY", "PLANNED", "AVAILABLE",
				checkedAt, "CLIENT", futureRediscovery, false, null, baseTime.plusSeconds(20));

		// Excluded cards (even newer timestamps)
		insertCard(userId, "https://www.musinsa.com/products/3004", "무신사", "구매완료제외", 49000L,
				"https://image.msscdn.net/3004.jpg", null, null, "NONE", "PURCHASED", "AVAILABLE", checkedAt, "SERVER",
				futureRediscovery, false, null, baseTime.plusSeconds(30));
		insertCard(userId, "https://www.musinsa.com/products/3005", "무신사", "삭제제외", 59000L,
				"https://image.msscdn.net/3005.jpg", null, null, "NONE", "PLANNED", "AVAILABLE", checkedAt, "SERVER",
				futureRediscovery, true, baseTime.plusSeconds(40), baseTime.plusSeconds(40));
		insertCard(otherUserId, "https://www.musinsa.com/products/3006", "무신사", "타인제외", 69000L,
				"https://image.msscdn.net/3006.jpg", null, null, "NONE", "PLANNED", "AVAILABLE", checkedAt, "SERVER",
				futureRediscovery, false, null, baseTime.plusSeconds(50));

		String body = mvc
				.perform(get(HOME_PATH).param("recentLimit", "2").header(HttpHeaders.AUTHORIZATION,
						"Bearer " + accessToken))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		JsonNode recentProducts = objectMapper.readTree(body).path("recentProducts");

		assertThat(recentProducts.size()).isEqualTo(2);

		JsonNode first = recentProducts.get(0);
		assertThat(first.path("cardId").asLong()).isEqualTo(newestId);
		assertThat(first.path("shop").asText()).isEqualTo("무신사");
		assertThat(first.path("productTitle").asText()).isEqualTo("최신상품");
		assertThat(first.path("price").asLong()).isEqualTo(39000L);
		assertThat(first.path("imageUrl").asText()).isEqualTo("https://image.msscdn.net/3003.jpg");
		assertThat(first.path("imageFileUrl").asText())
				.startsWith("https://storage.local/signed/thumbnails/2026/11/recent-1.webp");
		assertThat(first.path("fallbackIcon").isNull()).isTrue();
		assertThat(first.path("imageStatus").asText()).isEqualTo("READY");
		assertThat(first.path("link").path("status").asText()).isEqualTo("AVAILABLE");
		assertThat(first.path("link").path("checkedAt").asText()).isEqualTo(checkedAt.toString());
		assertThat(first.path("link").path("source").asText()).isEqualTo("CLIENT");
		assertThat(first.path("createdAt").asText()).isEqualTo(baseTime.plusSeconds(20).toString());

		JsonNode second = recentProducts.get(1);
		assertThat(second.path("cardId").asLong()).isEqualTo(secondNewestId);
		assertThat(second.path("shop").isNull()).isTrue();
		assertThat(second.path("productTitle").asText()).isEqualTo("대체아이콘상품");
		assertThat(second.path("price").asLong()).isEqualTo(25000L);
		assertThat(second.path("imageUrl").isNull()).isTrue();
		assertThat(second.path("imageFileUrl").isNull()).isTrue();
		assertThat(second.path("fallbackIcon").asText()).isEqualTo("ACCESSORY");
		assertThat(second.path("imageStatus").asText()).isEqualTo("NONE");
		assertThat(second.path("link").path("status").asText()).isEqualTo("UNKNOWN");
		assertThat(second.path("link").path("checkedAt").isNull()).isTrue();
		assertThat(second.path("link").path("source").isNull()).isTrue();
		assertThat(second.path("createdAt").asText()).isEqualTo(baseTime.plusSeconds(10).toString());
	}

	@Test
	void 기본조회개수를초과하는상품이저장된상태_파라미터없이홈요약을조회하면_최신10건만반환한다() throws Exception {
		Instant baseTime = Instant.parse("2026-11-12T00:00:00Z");
		Instant futureRediscovery = Instant.now().plus(14, ChronoUnit.DAYS);

		for (int index = 1; index <= 12; index++) {
			insertCard(userId, "https://www.musinsa.com/products/40" + index, "무신사", "상품" + index, 10000L * index, null,
					null, "TOP", "NONE", "PLANNED", "UNKNOWN", null, null, futureRediscovery, false, null,
					baseTime.plusSeconds(index));
		}

		mvc.perform(get(HOME_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.recentProducts.length()").value(10))
				.andExpect(jsonPath("$.recentProducts[0].productTitle").value("상품12"))
				.andExpect(jsonPath("$.recentProducts[9].productTitle").value("상품3"));
	}

	@Test
	void 여러보드에동시연결된상품이있는상태_홈요약을조회하면_각보드에는모두집계되고최근상품과재발견에는한번만집계된다() throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		Instant past = now.minus(1, ChronoUnit.DAYS);
		Long boardA = insertBoard(userId, "보드A", true, false, null, now.minusSeconds(20));
		Long boardB = insertBoard(userId, "보드B", true, false, null, now.minusSeconds(10));
		FileAsset storedAsset = fileAssetRepository.save(FileAsset.create("thumbnails/2026/11/shared-card.webp", userId,
				"shared.webp", "image/webp", 512L, now));
		Long sharedCardId = insertCard(userId, "https://www.musinsa.com/products/5001", "무신사", "공유상품", 45000L,
				"https://image.msscdn.net/5001.jpg", storedAsset.getId(), null, "READY", "PLANNED", "AVAILABLE", past,
				"SERVER", past, false, null, past);

		linkCardToBoard(boardA, sharedCardId, now.minusSeconds(5));
		linkCardToBoard(boardB, sharedCardId, now.minusSeconds(3));

		mvc.perform(
				get(HOME_PATH).param("recentLimit", "50").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isOk()).andExpect(jsonPath("$.rediscoveryCount").value(1))
				.andExpect(jsonPath("$.boards.length()").value(2))
				.andExpect(jsonPath("$.boards[0].boardId").value(boardB))
				.andExpect(jsonPath("$.boards[0].productCount").value(1))
				.andExpect(jsonPath("$.boards[0].previewImages[0].cardId").value(sharedCardId))
				.andExpect(jsonPath("$.boards[1].boardId").value(boardA))
				.andExpect(jsonPath("$.boards[1].productCount").value(1))
				.andExpect(jsonPath("$.boards[1].previewImages[0].cardId").value(sharedCardId))
				.andExpect(jsonPath("$.recentProducts.length()").value(1))
				.andExpect(jsonPath("$.recentProducts[0].cardId").value(sharedCardId));
	}

	@ParameterizedTest
	@ValueSource(ints = {-5, 0, 51, 100})
	void 범위를벗어난recentLimit파라미터_홈요약을조회하면_400을반환한다(int invalidLimit) throws Exception {
		mvc.perform(get(HOME_PATH).param("recentLimit", String.valueOf(invalidLimit)).header(HttpHeaders.AUTHORIZATION,
				"Bearer " + accessToken)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void 숫자가아닌recentLimit파라미터_홈요약을조회하면_400을반환한다() throws Exception {
		mvc.perform(
				get(HOME_PATH).param("recentLimit", "abc").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	private void clearDatabase() {
		jdbc.update("DELETE FROM ziper.product_board_card");
		jdbc.update("DELETE FROM ziper.product_card_tags");
		jdbc.update("DELETE FROM ziper.user_card_events");
		jdbc.update("DELETE FROM ziper.image_mirror_jobs");
		jdbc.update("DELETE FROM ziper.product_cards");
		jdbc.update("DELETE FROM ziper.product_link_reports");
		jdbc.update("DELETE FROM ziper.product_links");
		jdbc.update("DELETE FROM ziper.product_boards");
		jdbc.update("DELETE FROM ziper.files");
		jdbc.update("DELETE FROM ziper.sessions");
		jdbc.update("DELETE FROM ziper.oauth_users");
		jdbc.update("DELETE FROM ziper.users");
	}

	private Long insertUser(String email, String nickname) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.users (email, nickname, status) VALUES (?, ?, 'ACTIVE') RETURNING id", Long.class,
				email, nickname);
	}

	private Long insertBoard(Long ownerId, String name, boolean modifiable, boolean deleted, Instant deletedAt,
			Instant createdAt) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.product_boards (user_id, name, modifiable, is_deleted, deleted_at, created_at) "
						+ "VALUES (?, ?, ?, ?, ?, ?) RETURNING id",
				Long.class, ownerId, name, modifiable, deleted, deletedAt == null ? null : Timestamp.from(deletedAt),
				Timestamp.from(createdAt));
	}

	private Long insertCard(Long ownerId, String productUrl, String shop, String productTitle, Long price,
			String imageUrl, Long imageFileId, String fallbackIcon, String imageStatus, String status,
			String linkStatus, Instant linkCheckedAt, String linkCheckSource, Instant nextRediscoveryAt,
			boolean deleted, Instant deletedAt, Instant createdAt) {
		return jdbc.queryForObject("""
				INSERT INTO ziper.product_cards (
				    user_id, product_url, shop, product_title, price, image_url, image_file_id,
				    fallback_icon, metadata_source, image_status, status, link_status, link_checked_at,
				    link_check_source, last_interacted_at, next_rediscovery_at, is_deleted, deleted_at,
				    created_at, updated_at
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				RETURNING id
				""", Long.class, ownerId, productUrl, shop, productTitle, price, imageUrl, imageFileId, fallbackIcon,
				imageStatus, status, linkStatus, linkCheckedAt == null ? null : Timestamp.from(linkCheckedAt),
				linkCheckSource, Timestamp.from(createdAt), Timestamp.from(nextRediscoveryAt), deleted,
				deletedAt == null ? null : Timestamp.from(deletedAt), Timestamp.from(createdAt),
				Timestamp.from(createdAt));
	}

	private void linkCardToBoard(Long boardId, Long cardId, Instant createdAt) {
		jdbc.update("INSERT INTO ziper.product_board_card (board_id, card_id, created_at) VALUES (?, ?, ?)", boardId,
				cardId, Timestamp.from(createdAt));
	}
}
