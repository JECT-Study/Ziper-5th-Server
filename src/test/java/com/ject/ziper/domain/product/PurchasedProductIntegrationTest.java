package com.ject.ziper.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PurchasedProductIntegrationTest extends PostgresTestSupport {
	private static final String LIST_PATH = "/api/v1/products/purchased";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JwtTokenProvider tokens;

	private Long userId;
	private Long otherUserId;
	private String token;

	@BeforeEach
	void setUp() {
		clearDatabase();
		userId = insertUser("purchase-owner@example.com", "구매주인");
		otherUserId = insertUser("purchase-other@example.com", "구매타인");
		token = tokens.issueAccessToken(userId);
	}

	@AfterEach
	void tearDown() {
		clearDatabase();
	}

	@Test
	void 두보드에담긴상품_구매완료하면_연결은유지하고일반보드에서제외하며목록에는한번만나온다() throws Exception {
		Long firstBoard = insertBoard("첫 보드");
		Long secondBoard = insertBoard("둘째 보드");
		Long cardId = insertCard(userId, "multi", 30000, false, false, 2);
		linkCard(firstBoard, cardId);
		linkCard(secondBoard, cardId);
		Long customTag = jdbc.queryForObject(
				"INSERT INTO ziper.tags (title, is_default, user_id) VALUES ('구매', FALSE, ?) RETURNING id", Long.class,
				userId);
		Long defaultTag = jdbc.queryForObject("SELECT id FROM ziper.tags WHERE title = '봄'", Long.class);
		jdbc.update("INSERT INTO ziper.product_card_tags (card_id, tag_id) VALUES (?, ?)", cardId, customTag);
		jdbc.update("INSERT INTO ziper.product_card_tags (card_id, tag_id) VALUES (?, ?)", cardId, defaultTag);

		mvc.perform(post("/api/v1/products/{cardId}/purchase", cardId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.cardId").value(cardId))
				.andExpect(jsonPath("$.status").value("PURCHASED"))
				.andExpect(jsonPath("$.affectedBoardCount").value(2));

		assertThat(jdbc.queryForObject("SELECT status FROM ziper.product_cards WHERE id = ?", String.class, cardId))
				.isEqualTo("PURCHASED");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ziper.product_board_card WHERE card_id = ?", Integer.class,
				cardId)).isEqualTo(2);
		for (Long boardId : List.of(firstBoard, secondBoard)) {
			mvc.perform(
					get("/api/v1/boards/{boardId}/products", boardId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
		}
		mvc.perform(get(LIST_PATH).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCount").value(1)).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].cardId").value(cardId))
				.andExpect(jsonPath("$.items[0].productUrl").value("https://shop.example/multi"))
				.andExpect(jsonPath("$.items[0].shop").value("테스트몰"))
				.andExpect(jsonPath("$.items[0].productTitle").value("상품 multi"))
				.andExpect(jsonPath("$.items[0].price").value(30000))
				.andExpect(jsonPath("$.items[0].imageUrl").value("https://image.example/multi.jpg"))
				.andExpect(jsonPath("$.items[0].tag.title").value("봄"));
	}

	@Test
	void 여러구매완료상품이있을때_네정렬과커서로조회하면_등록시각과가격순서대로중복없이반환한다() throws Exception {
		Long first = insertCard(userId, "first", 200, true, false, 4);
		Long second = insertCard(userId, "second", 100, true, false, 3);
		Long third = insertCard(userId, "third", 300, true, false, 2);
		Long fourth = insertCard(userId, "fourth", 100, true, false, 1);
		insertCard(userId, "planned", 10, false, false, 0);
		insertCard(userId, "deleted", 10, true, true, 0);
		insertCard(otherUserId, "other", 10, true, false, 0);

		assertPageOrder(null, List.of(fourth, third, second, first));
		assertPageOrder("RECENT", List.of(fourth, third, second, first));
		assertPageOrder("OLDEST", List.of(first, second, third, fourth));
		assertPageOrder("PRICE_ASC", List.of(fourth, second, first, third));
		assertPageOrder("PRICE_DESC", List.of(third, first, fourth, second));
	}

	@Test
	void 타인또는삭제된상품과잘못된조회값_요청하면_404또는400을반환한다() throws Exception {
		Long other = insertCard(otherUserId, "other-purchase", 100, false, false, 1);
		Long deleted = insertCard(userId, "deleted-purchase", 100, false, true, 1);
		for (Long cardId : List.of(other, deleted, 999999L)) {
			mvc.perform(
					post("/api/v1/products/{cardId}/purchase", cardId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
					.andExpect(status().isNotFound());
		}
		mvc.perform(get(LIST_PATH).param("sort", "BAD").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isBadRequest());
		mvc.perform(get(LIST_PATH).param("size", "101").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isBadRequest());
		mvc.perform(get(LIST_PATH).param("cursor", "invalid").header(HttpHeaders.AUTHORIZATION, bearer(token)))
				.andExpect(status().isBadRequest());
		mvc.perform(get(LIST_PATH)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/products/{cardId}/purchase", deleted)).andExpect(status().isUnauthorized());
	}

	@Test
	void 저장소이미지가있는구매완료상품_목록을조회하면_원본이미지보다서명URL을우선한다() throws Exception {
		Long cardId = insertCard(userId, "signed", 500, true, false, 1);
		Long fileId = jdbc.queryForObject("""
				INSERT INTO ziper.files (storage_key, original_filename, content_type, file_size, owner_user_id)
				VALUES ('thumbnails/purchased.webp', 'purchased.webp', 'image/webp', 10, ?)
				RETURNING id
				""", Long.class, userId);
		jdbc.update("UPDATE ziper.product_cards SET image_file_id = ?, image_status = 'READY' WHERE id = ?", fileId,
				cardId);

		mvc.perform(get(LIST_PATH).header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].imageUrl")
						.value(containsString("https://storage.local/signed/thumbnails/purchased.webp")))
				.andExpect(jsonPath("$.items[0].productUrl").value("https://shop.example/signed"))
				.andExpect(jsonPath("$.items[0].tag").isEmpty());
	}

	private void assertPageOrder(String sort, List<Long> expected) throws Exception {
		List<Long> observed = new ArrayList<>();
		String cursor = null;
		do {
			var request = get(LIST_PATH).param("size", "2").header(HttpHeaders.AUTHORIZATION, bearer(token));
			if (sort != null) {
				request.param("sort", sort);
			}
			if (cursor != null) {
				request.param("cursor", cursor);
			}
			MvcResult result = mvc.perform(request).andExpect(status().isOk())
					.andExpect(jsonPath("$.totalCount").value(4)).andReturn();
			JsonNode page = mapper.readTree(result.getResponse().getContentAsString());
			page.path("items").forEach(item -> observed.add(item.path("cardId").asLong()));
			cursor = page.path("nextCursor").isNull() ? null : page.path("nextCursor").asText();
			assertThat(page.path("hasNext").asBoolean()).isEqualTo(cursor != null);
		} while (cursor != null);
		assertThat(observed).containsExactlyElementsOf(expected);
	}

	private Long insertUser(String email, String nickname) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.users (email, nickname, status) VALUES (?, ?, 'ACTIVE') RETURNING id", Long.class,
				email, nickname);
	}

	private Long insertBoard(String name) {
		return jdbc.queryForObject("INSERT INTO ziper.product_boards (user_id, name) VALUES (?, ?) RETURNING id",
				Long.class, userId, name);
	}

	private Long insertCard(Long ownerId, String suffix, long price, boolean purchased, boolean deleted, int daysAgo) {
		Instant created = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
		return jdbc.queryForObject("""
				INSERT INTO ziper.product_cards (
				    user_id, product_url, product_title, shop, price, image_url,
				    status, is_deleted, deleted_at, created_at, updated_at
				) VALUES (?, ?, ?, '테스트몰', ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
				RETURNING id
				""", Long.class, ownerId, "https://shop.example/" + suffix, "상품 " + suffix, price,
				"https://image.example/" + suffix + ".jpg", purchased ? "PURCHASED" : "PLANNED", deleted,
				deleted ? Timestamp.from(created) : null, Timestamp.from(created));
	}

	private void linkCard(Long boardId, Long cardId) {
		jdbc.update("INSERT INTO ziper.product_board_card (board_id, card_id) VALUES (?, ?)", boardId, cardId);
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

	private static String bearer(String token) {
		return "Bearer " + token;
	}
}
