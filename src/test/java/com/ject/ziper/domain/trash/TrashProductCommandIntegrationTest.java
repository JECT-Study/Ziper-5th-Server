package com.ject.ziper.domain.trash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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

import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TrashProductCommandIntegrationTest extends PostgresTestSupport {
	private static final String PATH = "/api/v1/trash/products/{productId}";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JwtTokenProvider tokens;

	private Long userId;
	private Long otherUserId;
	private String accessToken;

	@BeforeEach
	void setUp() {
		clearDatabase();
		userId = insertUser("trash-delete-owner@example.com", "휴지통삭제주인");
		otherUserId = insertUser("trash-delete-other@example.com", "휴지통삭제타인");
		accessToken = tokens.issueAccessToken(userId);
	}

	@AfterEach
	void tearDown() {
		clearDatabase();
	}

	@Test
	void 휴지통카드에연관행이있을때_영구삭제하면_카드와연관행이삭제되고목록에서도사라진다() throws Exception {
		Long cardId = insertCard(userId, "delete-target", true, Instant.now().minus(1, ChronoUnit.DAYS));
		Long remainingCardId = insertCard(userId, "keep", true, Instant.now().minus(2, ChronoUnit.DAYS));
		Long boardId = jdbc.queryForObject(
				"INSERT INTO ziper.product_boards (user_id, name) VALUES (?, '보관 보드') RETURNING id", Long.class,
				userId);
		Long tagId = jdbc.queryForObject("SELECT id FROM ziper.tags WHERE title = '봄'", Long.class);
		jdbc.update("INSERT INTO ziper.product_board_card (board_id, card_id) VALUES (?, ?)", boardId, cardId);
		jdbc.update("INSERT INTO ziper.product_card_tags (card_id, tag_id) VALUES (?, ?)", cardId, tagId);
		jdbc.update("INSERT INTO ziper.user_card_events (user_id, card_id) VALUES (?, ?)", userId, cardId);
		jdbc.update("""
				INSERT INTO ziper.image_mirror_jobs (card_id, user_id, source_url, source_url_hash, host)
				VALUES (?, ?, 'https://image.example/item.jpg', ?, 'image.example')
				""", cardId, userId, "a".repeat(64));

		mvc.perform(delete(PATH, cardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isNoContent());

		assertThat(count("product_cards", cardId)).isZero();
		assertThat(count("product_board_card", cardId)).isZero();
		assertThat(count("product_card_tags", cardId)).isZero();
		assertThat(count("user_card_events", cardId)).isZero();
		assertThat(count("image_mirror_jobs", cardId)).isZero();
		assertThat(count("product_cards", remainingCardId)).isOne();
		mvc.perform(get("/api/v1/trash/products").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].cardId").value(remainingCardId));
	}

	@Test
	void 활성카드나타인카드나없는카드일때_영구삭제하면_404이고카드를유지한다() throws Exception {
		Long activeCardId = insertCard(userId, "active", false, null);
		Long otherCardId = insertCard(otherUserId, "other", true, Instant.now().minus(1, ChronoUnit.DAYS));
		Long expiredCardId = insertCard(userId, "expired", true, Instant.now().minus(31, ChronoUnit.DAYS));

		mvc.perform(delete(PATH, activeCardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));
		mvc.perform(delete(PATH, otherCardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));
		mvc.perform(delete(PATH, 999999L).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));
		assertThat(count("product_cards", activeCardId)).isOne();
		assertThat(count("product_cards", otherCardId)).isOne();

		mvc.perform(delete(PATH, expiredCardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isNoContent());
		mvc.perform(delete(PATH, expiredCardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));
	}

	@Test
	void 인증토큰이없을때_영구삭제하면_401을반환한다() throws Exception {
		mvc.perform(delete(PATH, 1L)).andExpect(status().isUnauthorized());
	}

	private int count(String table, Long cardId) {
		String key = "product_cards".equals(table) ? "id" : "card_id";
		return jdbc.queryForObject("SELECT COUNT(*) FROM ziper." + table + " WHERE " + key + " = ?", Integer.class,
				cardId);
	}

	private Long insertUser(String email, String nickname) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.users (email, nickname, status) VALUES (?, ?, 'ACTIVE') RETURNING id", Long.class,
				email, nickname);
	}

	private Long insertCard(Long ownerId, String path, boolean deleted, Instant deletedAt) {
		return jdbc.queryForObject("""
				INSERT INTO ziper.product_cards (
				    user_id, product_url, product_title, price, image_url, is_deleted, deleted_at
				)
				VALUES (?, ?, '테스트 상품', 1000, 'https://image.example/item.jpg', ?, ?)
				RETURNING id
				""", Long.class, ownerId, "https://shop.example/" + path, deleted,
				deletedAt == null ? null : Timestamp.from(deletedAt));
	}

	private void clearDatabase() {
		jdbc.update("DELETE FROM ziper.image_mirror_jobs");
		jdbc.update("DELETE FROM ziper.user_card_events");
		jdbc.update("DELETE FROM ziper.product_card_tags");
		jdbc.update("DELETE FROM ziper.product_board_card");
		jdbc.update("DELETE FROM ziper.product_cards");
		jdbc.update("DELETE FROM ziper.product_boards");
		jdbc.update("DELETE FROM ziper.users");
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}
}
