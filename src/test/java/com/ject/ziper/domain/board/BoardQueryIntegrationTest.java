package com.ject.ziper.domain.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.domain.board.entity.ProductBoard;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;
import com.ject.ziper.global.storage.ObjectStorageClient;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BoardQueryIntegrationTest extends PostgresTestSupport {
	private static final String BOARDS_PATH = "/api/v1/boards";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@MockitoSpyBean
	private ObjectStorageClient objectStorageClient;

	private Long userId;
	private Long otherUserId;
	private Long defaultBoardId;
	private String accessToken;
	private String otherAccessToken;

	@BeforeEach
	void setUp() {
		clearDatabase();
		userId = insertUser("board-query-owner@example.com", "조회오너");
		otherUserId = insertUser("board-query-other@example.com", "다른조회자");
		Instant baseTime = Instant.parse("2026-11-20T00:00:00Z");
		defaultBoardId = insertBoard(userId, ProductBoard.DEFAULT_BOARD_NAME, false, false, null, baseTime);
		insertBoard(otherUserId, ProductBoard.DEFAULT_BOARD_NAME, false, false, null, baseTime);
		accessToken = jwtTokenProvider.issueAccessToken(userId);
		otherAccessToken = jwtTokenProvider.issueAccessToken(otherUserId);
	}

	@AfterEach
	void tearDown() {
		clearDatabase();
	}

	@Test
	void 인증토큰이없는요청_보드목록과상세상품조회를호출하면_401을반환한다() throws Exception {
		mvc.perform(get(BOARDS_PATH)).andExpect(status().isUnauthorized());
		mvc.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")).andExpect(status().isUnauthorized());
	}

	@Test
	void 기본보드와커스텀보드가존재할때_보드목록을조회하면_기본보드고정없이순수최신생성순으로반환한다() throws Exception {
		Instant t1 = Instant.parse("2026-11-20T01:00:00Z");
		Instant t2 = Instant.parse("2026-11-20T02:00:00Z");
		Instant t3 = Instant.parse("2026-11-20T03:00:00Z");

		Long olderCustomId = insertBoard(userId, "먼저만든보드", true, false, null, t1);
		Long sameTimeFirstId = insertBoard(userId, "동시보드하나", true, false, null, t2);
		Long sameTimeSecondId = insertBoard(userId, "동시보드둘", true, false, null, t2);
		insertBoard(userId, "삭제된보드", true, true, t3, t3);
		insertBoard(otherUserId, "타인보드", true, false, null, t3);

		MvcResult result = mvc.perform(get(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andReturn();

		JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
		JsonNode boards = root.path("boards");
		assertThat(boards).hasSize(4);
		assertThat(boards.get(0).path("boardId").asLong()).isEqualTo(sameTimeSecondId);
		assertThat(boards.get(0).path("modifiable").asBoolean()).isTrue();
		assertThat(boards.get(1).path("boardId").asLong()).isEqualTo(sameTimeFirstId);
		assertThat(boards.get(2).path("boardId").asLong()).isEqualTo(olderCustomId);
		assertThat(boards.get(3).path("boardId").asLong()).isEqualTo(defaultBoardId);
		assertThat(boards.get(3).path("name").asText()).isEqualTo(ProductBoard.DEFAULT_BOARD_NAME);
		assertThat(boards.get(3).path("modifiable").asBoolean()).isFalse();
		assertThat(boards.get(3).path("createdAt").asText()).isNotBlank();
		assertThat(root.path("purchasedSummary").path("productCount").asInt()).isZero();
		assertThat(root.path("purchasedSummary").path("previewImages")).isEmpty();
	}

	@Test
	void 구매예정과구매완료와삭제된카드가섞여있을때_보드목록을조회하면_구매예정개수와최대3장미리보기우선순위를반환한다() throws Exception {
		Instant base = Instant.parse("2026-11-20T10:00:00Z");
		Long emptyBoardId = insertBoard(userId, "빈보드", true, false, null, base.plusSeconds(60));
		Long fileId = insertFile("thumbnails/2026/11/preview-priority.webp");

		Long card1 = insertCard(userId, "https://shop.example/p1", "첫번째상품", 10000L, "https://cdn.example/p1.jpg", null,
				"TOP", "PLANNED", "READY", false, null, base);
		Long card2 = insertCard(userId, "https://shop.example/p2", "두번째상품", 20000L, null, null, "SKIRT", "PLANNED",
				"NONE", false, null, base.plusSeconds(10));
		Long card3 = insertCard(userId, "https://shop.example/p3", "세번째상품", 30000L, "https://cdn.example/p3.jpg", null,
				null, "PLANNED", "PENDING", false, null, base.plusSeconds(20));
		Long card4 = insertCard(userId, "https://shop.example/p4", "네번째상품", 40000L, "https://cdn.example/p4-raw.jpg",
				fileId, "PANTS", "PLANNED", "READY", false, null, base.plusSeconds(30));
		Long purchasedCard = insertCard(userId, "https://shop.example/purchased", "구매완료상품", 50000L,
				"https://cdn.example/purchased.jpg", null, "TOP", "PURCHASED", "NONE", false, null,
				base.plusSeconds(40));
		Long deletedCard = insertCard(userId, "https://shop.example/deleted", "삭제상품", 60000L,
				"https://cdn.example/deleted.jpg", null, "TOP", "PLANNED", "NONE", true, base.plusSeconds(50),
				base.plusSeconds(50));

		linkCardToBoard(defaultBoardId, card1, base.plusSeconds(1));
		linkCardToBoard(defaultBoardId, card2, base.plusSeconds(2));
		linkCardToBoard(defaultBoardId, card3, base.plusSeconds(3));
		linkCardToBoard(defaultBoardId, card4, base.plusSeconds(4));
		linkCardToBoard(defaultBoardId, purchasedCard, base.plusSeconds(5));
		linkCardToBoard(defaultBoardId, deletedCard, base.plusSeconds(6));

		MvcResult result = mvc.perform(get(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isOk()).andReturn();

		JsonNode boards = objectMapper.readTree(result.getResponse().getContentAsString()).path("boards");
		assertThat(boards).hasSize(2);

		JsonNode emptyBoard = boards.get(0);
		assertThat(emptyBoard.path("boardId").asLong()).isEqualTo(emptyBoardId);
		assertThat(emptyBoard.path("productCount").asInt()).isZero();
		assertThat(emptyBoard.path("previewImages")).isEmpty();

		JsonNode defaultBoard = boards.get(1);
		assertThat(defaultBoard.path("boardId").asLong()).isEqualTo(defaultBoardId);
		assertThat(defaultBoard.path("productCount").asInt()).isEqualTo(4);

		JsonNode previews = defaultBoard.path("previewImages");
		assertThat(previews).hasSize(3);

		assertThat(previews.get(0).path("cardId").asLong()).isEqualTo(card4);
		assertThat(previews.get(0).path("imageUrl").asText())
				.contains("https://storage.local/signed/thumbnails/2026/11/preview-priority.webp");
		assertThat(previews.get(0).path("fallbackIcon").asText()).isEqualTo("PANTS");

		assertThat(previews.get(1).path("cardId").asLong()).isEqualTo(card3);
		assertThat(previews.get(1).path("imageUrl").asText()).isEqualTo("https://cdn.example/p3.jpg");
		assertThat(previews.get(1).path("fallbackIcon").isNull()).isTrue();

		assertThat(previews.get(2).path("cardId").asLong()).isEqualTo(card2);
		assertThat(previews.get(2).path("imageUrl").isNull()).isTrue();
		assertThat(previews.get(2).path("fallbackIcon").asText()).isEqualTo("SKIRT");
	}

	@Test
	void 구매완료카드가여러개존재할때_보드목록을조회하면_구매완료요약개수와최신수정순최대3장을반환한다() throws Exception {
		Instant base = Instant.parse("2026-11-20T12:00:00Z");
		Long fileId = insertFile("thumbnails/2026/11/purchased-thumb.webp");

		insertCard(userId, "https://shop.example/buy1", "구매1", 10000L, "https://cdn.example/buy1.jpg", null, "TOP",
				"PURCHASED", "NONE", false, null, base);
		Long buy2 = insertCard(userId, "https://shop.example/buy2", "구매2", 20000L, null, null, "BEAUTY", "PURCHASED",
				"NONE", false, null, base.plusSeconds(10));
		Long buy3 = insertCard(userId, "https://shop.example/buy3", "구매3", 30000L, "https://cdn.example/buy3.jpg", null,
				null, "PURCHASED", "NONE", false, null, base.plusSeconds(20));
		Long buy4 = insertCard(userId, "https://shop.example/buy4", "구매4", 40000L, "https://cdn.example/buy4-raw.jpg",
				fileId, "ACCESSORY", "PURCHASED", "READY", false, null, base.plusSeconds(30));
		insertCard(userId, "https://shop.example/buy-deleted", "삭제구매", 50000L, "https://cdn.example/buy-del.jpg", null,
				"TOP", "PURCHASED", "NONE", true, base.plusSeconds(40), base.plusSeconds(40));
		insertCard(userId, "https://shop.example/planned-only", "예정상품", 15000L, "https://cdn.example/planned.jpg", null,
				"TOP", "PLANNED", "NONE", false, null, base.plusSeconds(50));
		insertCard(otherUserId, "https://shop.example/other-buy", "타인구매", 99000L, "https://cdn.example/other.jpg", null,
				"TOP", "PURCHASED", "NONE", false, null, base.plusSeconds(60));

		MvcResult result = mvc.perform(get(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isOk()).andReturn();

		JsonNode purchased = objectMapper.readTree(result.getResponse().getContentAsString()).path("purchasedSummary");
		assertThat(purchased.path("productCount").asInt()).isEqualTo(4);
		JsonNode previews = purchased.path("previewImages");
		assertThat(previews).hasSize(3);

		assertThat(previews.get(0).path("cardId").asLong()).isEqualTo(buy4);
		assertThat(previews.get(0).path("imageUrl").asText())
				.contains("https://storage.local/signed/thumbnails/2026/11/purchased-thumb.webp");
		assertThat(previews.get(0).path("fallbackIcon").asText()).isEqualTo("ACCESSORY");

		assertThat(previews.get(1).path("cardId").asLong()).isEqualTo(buy3);
		assertThat(previews.get(1).path("imageUrl").asText()).isEqualTo("https://cdn.example/buy3.jpg");

		assertThat(previews.get(2).path("cardId").asLong()).isEqualTo(buy2);
		assertThat(previews.get(2).path("imageUrl").isNull()).isTrue();
		assertThat(previews.get(2).path("fallbackIcon").asText()).isEqualTo("BEAUTY");
	}

	@Test
	void 보드에여러상품이존재할때_네가지정렬기준으로조회하면_정확한순서로반환한다() throws Exception {
		Instant base = Instant.parse("2026-11-20T14:00:00Z");
		Long cardA = insertCard(userId, "https://shop.example/sort-a", "상품A", 30000L, null, null, "TOP", "PLANNED",
				"NONE", false, null, base);
		Long cardB = insertCard(userId, "https://shop.example/sort-b", "상품B", 10000L, null, null, "TOP", "PLANNED",
				"NONE", false, null, base);
		Long cardC = insertCard(userId, "https://shop.example/sort-c", "상품C", 30000L, null, null, "TOP", "PLANNED",
				"NONE", false, null, base);
		Long cardD = insertCard(userId, "https://shop.example/sort-d", "상품D", 50000L, null, null, "TOP", "PLANNED",
				"NONE", false, null, base);

		linkCardToBoard(defaultBoardId, cardA, base.plusSeconds(1));
		linkCardToBoard(defaultBoardId, cardB, base.plusSeconds(2));
		linkCardToBoard(defaultBoardId, cardC, base.plusSeconds(1));
		linkCardToBoard(defaultBoardId, cardD, base.plusSeconds(4));

		assertThat(fetchCardIds(defaultBoardId, null)).containsExactly(cardD, cardB, cardC, cardA);
		assertThat(fetchCardIds(defaultBoardId, "RECENT")).containsExactly(cardD, cardB, cardC, cardA);
		assertThat(fetchCardIds(defaultBoardId, "OLDEST")).containsExactly(cardA, cardC, cardB, cardD);
		assertThat(fetchCardIds(defaultBoardId, "PRICE_ASC")).containsExactly(cardB, cardC, cardA, cardD);
		assertThat(fetchCardIds(defaultBoardId, "price_asc")).containsExactly(cardB, cardC, cardA, cardD);
		assertThat(fetchCardIds(defaultBoardId, "PRICE_DESC")).containsExactly(cardD, cardC, cardA, cardB);
	}

	@Test
	void 해시태그가연결된보드상품이있을때_태그필터로조회하면_일치하는상품과정렬된태그목록을반환한다() throws Exception {
		Instant base = Instant.parse("2026-11-20T15:00:00Z");
		Long springTagId = findDefaultTagId("봄");
		Long summerTagId = findDefaultTagId("여름");
		Long officeTagId = insertCustomTag(userId, "출근룩");
		Long saleTagId = insertCustomTag(userId, "세일");

		Long cardA = insertCard(userId, "https://shop.example/tag-a", "봄출근상품", 29000L, null, null, "TOP", "PLANNED",
				"NONE", false, null, base);
		Long cardB = insertCard(userId, "https://shop.example/tag-b", "여름상품", 19000L, null, null, "TOP", "PLANNED",
				"NONE", false, null, base);
		Long cardC = insertCard(userId, "https://shop.example/tag-c", "세일상품", 39000L, null, null, "TOP", "PLANNED",
				"NONE", false, null, base);
		Long cardD = insertCard(userId, "https://shop.example/tag-d", "무태그상품", 49000L, null, null, "TOP", "PLANNED",
				"NONE", false, null, base);

		linkCardToBoard(defaultBoardId, cardA, base.plusSeconds(1));
		linkCardToBoard(defaultBoardId, cardB, base.plusSeconds(2));
		linkCardToBoard(defaultBoardId, cardC, base.plusSeconds(3));
		linkCardToBoard(defaultBoardId, cardD, base.plusSeconds(4));

		linkTagToCard(cardA, officeTagId);
		linkTagToCard(cardA, springTagId);
		linkTagToCard(cardB, summerTagId);
		linkTagToCard(cardC, saleTagId);

		MvcResult singleTagResult = mvc.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")
				.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("tagIds", String.valueOf(springTagId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.productCount").value(1)).andReturn();

		JsonNode singleItems = objectMapper.readTree(singleTagResult.getResponse().getContentAsString()).path("items");
		assertThat(singleItems).hasSize(1);
		assertThat(singleItems.get(0).path("cardId").asLong()).isEqualTo(cardA);
		JsonNode tags = singleItems.get(0).path("tags");
		assertThat(tags).hasSize(2);
		assertThat(tags.get(0).path("tagId").asLong()).isEqualTo(springTagId);
		assertThat(tags.get(0).path("title").asText()).isEqualTo("봄");
		assertThat(tags.get(0).path("isDefault").asBoolean()).isTrue();
		assertThat(tags.get(1).path("tagId").asLong()).isEqualTo(officeTagId);
		assertThat(tags.get(1).path("title").asText()).isEqualTo("출근룩");
		assertThat(tags.get(1).path("isDefault").asBoolean()).isFalse();

		MvcResult multiTagResult = mvc
				.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")
						.header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
						.param("tagIds", String.valueOf(springTagId), String.valueOf(saleTagId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.productCount").value(2)).andReturn();

		JsonNode multiItems = objectMapper.readTree(multiTagResult.getResponse().getContentAsString()).path("items");
		assertThat(extractCardIds(multiItems)).containsExactly(cardC, cardA);
	}

	@Test
	void 상품이페이지크기보다많을때_커서와크기로조회하면_중복없이끝까지페이지네이션된다() throws Exception {
		Instant base = Instant.parse("2026-11-20T16:00:00Z");
		List<Long> insertedNewestFirst = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			Long cardId = insertCard(userId, "https://shop.example/page-" + i, "페이지상품" + i, i * 10000L, null, null,
					"TOP", "PLANNED", "NONE", false, null, base);
			linkCardToBoard(defaultBoardId, cardId, base.plusSeconds(i));
			insertedNewestFirst.addFirst(cardId);
		}

		MvcResult page1Result = mvc
				.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")
						.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("size", "2"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.productCount").value(5))
				.andExpect(jsonPath("$.hasNext").value(true)).andExpect(jsonPath("$.nextCursor").isNotEmpty())
				.andReturn();
		JsonNode page1 = objectMapper.readTree(page1Result.getResponse().getContentAsString());
		assertThat(extractCardIds(page1.path("items"))).containsExactlyElementsOf(insertedNewestFirst.subList(0, 2));

		Long newest = insertCard(userId, "https://shop.example/page-new", "새 상품", 10000L, null, null, "TOP", "PLANNED",
				"NONE", false, null, base);
		linkCardToBoard(defaultBoardId, newest, base.plusSeconds(6));

		String cursor = page1.path("nextCursor").asText();
		Long otherBoard = insertBoard(userId, "다른 보드", true, false, null, base);
		mvc.perform(get(BOARDS_PATH + "/" + otherBoard + "/products")
				.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("cursor", cursor))
				.andExpect(status().isBadRequest());
		mvc.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")
				.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("cursor", cursor).param("sort", "OLDEST"))
				.andExpect(status().isBadRequest());

		MvcResult page2Result = mvc
				.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")
						.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("size", "2")
						.param("cursor", cursor))
				.andExpect(status().isOk()).andExpect(jsonPath("$.productCount").value(6))
				.andExpect(jsonPath("$.hasNext").value(true)).andExpect(jsonPath("$.nextCursor").isNotEmpty())
				.andReturn();
		JsonNode page2 = objectMapper.readTree(page2Result.getResponse().getContentAsString());
		assertThat(extractCardIds(page2.path("items"))).containsExactlyElementsOf(insertedNewestFirst.subList(2, 4));

		jdbc.update("DELETE FROM ziper.product_board_card WHERE board_id = ? AND card_id = ?", defaultBoardId,
				insertedNewestFirst.get(3));

		MvcResult page3Result = mvc
				.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")
						.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("size", "2")
						.param("cursor", page2.path("nextCursor").asText()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.productCount").value(5))
				.andExpect(jsonPath("$.hasNext").value(false)).andExpect(jsonPath("$.nextCursor").doesNotExist())
				.andReturn();
		JsonNode page3 = objectMapper.readTree(page3Result.getResponse().getContentAsString());
		assertThat(extractCardIds(page3.path("items"))).containsExactlyElementsOf(insertedNewestFirst.subList(4, 5));

		mvc.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")
				.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("size", "5")).andExpect(status().isOk())
				.andExpect(jsonPath("$.hasNext").value(false)).andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void 여러미리보기파일중하나의서명이누락되면_보드를조회하면_한번에발급하고해당이미지만폴백한다() throws Exception {
		Instant base = Instant.parse("2026-11-20T17:00:00Z");
		String signedKey = "thumbnails/board-signed.webp";
		String missingKey = "thumbnails/board-missing.webp";
		String signedUrl = "https://storage.local/board-signed.webp";
		String originalUrl = "https://shop.example/board-original.webp";
		Long signed = insertCard(userId, "https://shop.example/signed", "서명 성공", 1000L, null, insertFile(signedKey),
				"TOP", "PLANNED", "READY", false, null, base);
		Long missing = insertCard(userId, "https://shop.example/missing", "서명 누락", 1000L, originalUrl,
				insertFile(missingKey), "TOP", "PLANNED", "READY", false, null, base);
		linkCardToBoard(defaultBoardId, missing, base);
		linkCardToBoard(defaultBoardId, signed, base.plusSeconds(1));

		doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			assertThat(invocation.<Collection<String>>getArgument(0)).containsExactlyInAnyOrder(signedKey, missingKey);
			return Map.of(signedKey, signedUrl);
		}).when(objectStorageClient).createSignedUrls(any(), any());

		mvc.perform(get(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))).andExpect(status().isOk())
				.andExpect(jsonPath("$.boards[0].previewImages[0].imageUrl").value(signedUrl))
				.andExpect(jsonPath("$.boards[0].previewImages[1].imageUrl").value(originalUrl));
		verify(objectStorageClient, times(1)).createSignedUrls(any(), any());

		mvc.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products").header(HttpHeaders.AUTHORIZATION,
				bearer(accessToken))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].imageFileUrl").value(signedUrl))
				.andExpect(jsonPath("$.items[1].imageFileUrl").doesNotExist())
				.andExpect(jsonPath("$.items[1].imageUrl").value(originalUrl));
		verify(objectStorageClient, times(2)).createSignedUrls(any(), any());
	}

	@Test
	void 구매완료와삭제된카드와리비전B필드가있을때_보드상세를조회하면_구매예정만노출하고모든필드를정확히반환한다() throws Exception {
		Instant base = Instant.parse("2026-11-20T17:00:00Z");
		Instant linkCheckedAt = Instant.parse("2026-11-20T17:30:00Z");
		Instant addedAt = Instant.parse("2026-11-20T17:45:00Z");
		Long fileId = insertFile("thumbnails/2026/11/detail-item.webp");
		Long springTagId = findDefaultTagId("봄");

		String longTitle = "스무글자를훨씬넘기는아주긴상품명전체노출테스트원본상품명";
		Long activeCardId = jdbc.queryForObject("""
				INSERT INTO ziper.product_cards (
				    user_id, product_url, shop, product_title, price, memo,
				    image_url, image_file_id, fallback_icon, image_status, status,
				    link_status, link_checked_at, link_check_source,
				    is_deleted, deleted_at, created_at, updated_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PLANNED', ?, ?, ?, FALSE, NULL, ?, ?)
				RETURNING id
				""", Long.class, userId, "https://www.musinsa.com/products/123456", "무신사", longTitle, 49000L,
				"가을 세일 대기", "https://cdn.example/raw.jpg", fileId, "TOP", "READY", "SOLD_OUT",
				Timestamp.from(linkCheckedAt), "CLIENT", Timestamp.from(base), Timestamp.from(base));
		linkCardToBoard(defaultBoardId, activeCardId, addedAt);
		linkTagToCard(activeCardId, springTagId);

		Long purchasedCardId = insertCard(userId, "https://shop.example/excl-buy", "제외구매", 10000L, null, null, "TOP",
				"PURCHASED", "NONE", false, null, base);
		Long deletedCardId = insertCard(userId, "https://shop.example/excl-del", "제외삭제", 10000L, null, null, "TOP",
				"PLANNED", "NONE", true, base, base);
		linkCardToBoard(defaultBoardId, purchasedCardId, addedAt.plusSeconds(10));
		linkCardToBoard(defaultBoardId, deletedCardId, addedAt.plusSeconds(20));

		MvcResult result = mvc
				.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products").header(HttpHeaders.AUTHORIZATION,
						bearer(accessToken)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.boardId").value(defaultBoardId))
				.andExpect(jsonPath("$.name").value(ProductBoard.DEFAULT_BOARD_NAME))
				.andExpect(jsonPath("$.modifiable").value(false)).andExpect(jsonPath("$.productCount").value(1))
				.andExpect(jsonPath("$.hasNext").value(false)).andReturn();

		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).path("items");
		assertThat(items).hasSize(1);
		JsonNode item = items.get(0);
		assertThat(item.path("cardId").asLong()).isEqualTo(activeCardId);
		assertThat(item.path("shop").asText()).isEqualTo("무신사");
		assertThat(item.path("productTitle").asText()).isEqualTo(longTitle);
		assertThat(item.path("price").asLong()).isEqualTo(49000L);
		assertThat(item.path("memo").asText()).isEqualTo("가을 세일 대기");
		assertThat(item.path("imageUrl").asText()).isEqualTo("https://cdn.example/raw.jpg");
		assertThat(item.path("imageFileUrl").asText())
				.contains("https://storage.local/signed/thumbnails/2026/11/detail-item.webp");
		assertThat(item.path("fallbackIcon").asText()).isEqualTo("TOP");
		assertThat(item.path("imageStatus").asText()).isEqualTo("READY");
		assertThat(item.path("link").path("status").asText()).isEqualTo("SOLD_OUT");
		assertThat(Instant.parse(item.path("link").path("checkedAt").asText())).isEqualTo(linkCheckedAt);
		assertThat(item.path("link").path("source").asText()).isEqualTo("CLIENT");
		assertThat(item.path("tags")).hasSize(1);
		assertThat(item.path("tags").get(0).path("tagId").asLong()).isEqualTo(springTagId);
		assertThat(Instant.parse(item.path("addedAt").asText())).isEqualTo(addedAt);
	}

	@Test
	void 잘못된정렬이나커서나크기파라미터_보드상세를조회하면_400오류를반환한다() throws Exception {
		mvc.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")
				.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("sort", "INVALID_SORT"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		mvc.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")
				.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("sort", ""))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		mvc.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")
				.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("cursor", "-1"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		mvc.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")
				.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("cursor", "abc"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		mvc.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")
				.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("size", "0"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		mvc.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")
				.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("size", "101"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		mvc.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products")
				.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("tagIds", "0"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void 타인보드나미존재보드나삭제된보드_보드상세를조회하면_404를반환한다() throws Exception {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
		Long otherBoardId = insertBoard(otherUserId, "타인커스텀", true, false, null, now);
		Long deletedBoardId = insertBoard(userId, "삭제보드", true, true, now, now);

		for (Long targetId : List.of(otherBoardId, 999_999L, deletedBoardId)) {
			mvc.perform(get(BOARDS_PATH + "/" + targetId + "/products").header(HttpHeaders.AUTHORIZATION,
					bearer(accessToken))).andExpect(status().isNotFound())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
					.andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));
		}

		mvc.perform(get(BOARDS_PATH + "/" + defaultBoardId + "/products").header(HttpHeaders.AUTHORIZATION,
				bearer(otherAccessToken))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));
	}

	private List<Long> fetchCardIds(Long boardId, String sort) throws Exception {
		List<Long> ids = new ArrayList<>();
		String cursor = "";
		for (int page = 0; page < 4; page++) {
			var request = get(BOARDS_PATH + "/" + boardId + "/products")
					.header(HttpHeaders.AUTHORIZATION, bearer(accessToken)).param("size", "1").param("cursor", cursor);
			if (sort != null) {
				request.param("sort", sort);
			}
			MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
			JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
			ids.addAll(extractCardIds(body.path("items")));
			if (!body.path("hasNext").asBoolean()) {
				return ids;
			}
			cursor = body.path("nextCursor").asText();
		}
		throw new AssertionError("4개의 상품을 조회한 뒤에도 다음 페이지가 남아 있습니다.");
	}

	private static List<Long> extractCardIds(JsonNode items) {
		List<Long> ids = new ArrayList<>();
		for (JsonNode item : items) {
			ids.add(item.path("cardId").asLong());
		}
		return ids;
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private Long insertUser(String email, String nickname) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.users (email, nickname, status) VALUES (?, ?, 'ACTIVE') RETURNING id", Long.class,
				email, nickname);
	}

	private Long insertBoard(Long ownerId, String name, boolean modifiable, boolean deleted, Instant deletedAt,
			Instant createdAt) {
		return jdbc.queryForObject("""
				INSERT INTO ziper.product_boards (user_id, name, modifiable, is_deleted, deleted_at, created_at)
				VALUES (?, ?, ?, ?, ?, ?)
				RETURNING id
				""", Long.class, ownerId, name, modifiable, deleted,
				deletedAt == null ? null : Timestamp.from(deletedAt), Timestamp.from(createdAt));
	}

	private Long insertFile(String storageKey) {
		return jdbc.queryForObject("""
				INSERT INTO ziper.files (storage_key, original_filename, content_type, file_size)
				VALUES (?, 'thumb.webp', 'image/webp', 2048)
				RETURNING id
				""", Long.class, storageKey);
	}

	private Long insertCard(Long ownerId, String url, String title, Long price, String imageUrl, Long imageFileId,
			String fallbackIcon, String status, String imageStatus, boolean deleted, Instant deletedAt,
			Instant timestamp) {
		return jdbc.queryForObject("""
				INSERT INTO ziper.product_cards (
				    user_id, product_url, product_title, price, image_url, image_file_id,
				    fallback_icon, status, image_status, is_deleted, deleted_at, created_at, updated_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				RETURNING id
				""", Long.class, ownerId, url, title, price, imageUrl, imageFileId, fallbackIcon, status, imageStatus,
				deleted, deletedAt == null ? null : Timestamp.from(deletedAt), Timestamp.from(timestamp),
				Timestamp.from(timestamp));
	}

	private void linkCardToBoard(Long boardId, Long cardId, Instant addedAt) {
		jdbc.update("INSERT INTO ziper.product_board_card (board_id, card_id, created_at) VALUES (?, ?, ?)", boardId,
				cardId, Timestamp.from(addedAt));
	}

	private Long findDefaultTagId(String title) {
		return jdbc.queryForObject("SELECT id FROM ziper.tags WHERE is_default = TRUE AND title = ?", Long.class,
				title);
	}

	private Long insertCustomTag(Long ownerId, String title) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.tags (title, is_default, user_id) VALUES (?, FALSE, ?) RETURNING id", Long.class,
				title, ownerId);
	}

	private void linkTagToCard(Long cardId, Long tagId) {
		jdbc.update("INSERT INTO ziper.product_card_tags (card_id, tag_id) VALUES (?, ?)", cardId, tagId);
	}

	private void clearDatabase() {
		jdbc.update("DELETE FROM ziper.product_card_tags");
		jdbc.update("DELETE FROM ziper.product_board_card");
		jdbc.update("DELETE FROM ziper.product_cards");
		jdbc.update("DELETE FROM ziper.product_boards");
		jdbc.update("DELETE FROM ziper.files");
		jdbc.update("DELETE FROM ziper.tags WHERE is_default = FALSE");
		jdbc.update("DELETE FROM ziper.users");
	}
}
