package com.ject.ziper.domain.trash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TrashRestoreIntegrationTest extends PostgresTestSupport {
	private static final String TRASH_PATH = "/api/v1/trash/products/{productId}";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JwtTokenProvider tokens;

	@Autowired
	private ObjectMapper mapper;

	private Long userId;
	private Long otherUserId;
	private String accessToken;

	@BeforeEach
	void setUp() {
		clearDatabase();
		userId = insertUser("restore-owner@example.com", "복구주인");
		otherUserId = insertUser("restore-other@example.com", "복구타인");
		accessToken = tokens.issueAccessToken(userId);
	}

	@AfterEach
	void tearDown() {
		clearDatabase();
	}

	@Test
	void 고아상품의복구보드목록에서_현재활성보드를선택하면_선택한보드에만복구된다() throws Exception {
		Long firstBoardId = insertBoard(userId, "첫 보드");
		Long secondBoardId = insertBoard(userId, "둘째 보드");
		Long newBoardId = insertBoard(userId, "새 선택 보드");
		Long deletedBoardId = insertBoard(userId, "삭제된 보드");
		Long cardId = insertCard(userId, "orphan", false, null);
		link(firstBoardId, cardId);
		link(secondBoardId, cardId);
		link(deletedBoardId, cardId);
		mvc.perform(delete("/api/v1/boards/{boardId}", deletedBoardId).header(HttpHeaders.AUTHORIZATION,
				bearer(accessToken))).andExpect(status().isNoContent());
		Long originalSecondLinkId = jdbc.queryForObject(
				"SELECT id FROM ziper.product_board_card WHERE board_id = ? AND card_id = ?", Long.class, secondBoardId,
				cardId);

		mvc.perform(delete("/api/v1/boards/{boardId}/products/{productId}", firstBoardId, cardId)
				.header(HttpHeaders.AUTHORIZATION, bearer(accessToken))).andExpect(status().isNoContent());
		assertThat(boardProductCount("/api/v1/boards", firstBoardId)).isZero();
		assertThat(boardProductCount("/api/v1/boards", secondBoardId)).isOne();
		assertThat(boardProductCount("/api/v1/home", firstBoardId)).isZero();
		assertThat(boardProductCount("/api/v1/home", secondBoardId)).isOne();
		mvc.perform(delete("/api/v1/boards/{boardId}/products/{productId}", secondBoardId, cardId)
				.header(HttpHeaders.AUTHORIZATION, bearer(accessToken))).andExpect(status().isNoContent());

		mvc.perform(get(TRASH_PATH + "/restore-boards", cardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.boards.length()").value(3))
				.andExpect(jsonPath("$.boards[0].boardId").value(newBoardId))
				.andExpect(jsonPath("$.boards[1].boardId").value(secondBoardId))
				.andExpect(jsonPath("$.boards[2].boardId").value(firstBoardId));

		mvc.perform(post(TRASH_PATH + "/restore", cardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetBoardIds\":[" + secondBoardId + "," + newBoardId + "]}"))
				.andExpect(status().isNoContent());

		mvc.perform(get("/api/v1/trash/products").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		mvc.perform(get("/api/v1/boards/{boardId}/products", firstBoardId).header(HttpHeaders.AUTHORIZATION,
				bearer(accessToken))).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		mvc.perform(get("/api/v1/boards/{boardId}/products", secondBoardId).header(HttpHeaders.AUTHORIZATION,
				bearer(accessToken))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].cardId").value(cardId));
		mvc.perform(get("/api/v1/boards/{boardId}/products", newBoardId).header(HttpHeaders.AUTHORIZATION,
				bearer(accessToken))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].cardId").value(cardId));
		assertThat(jdbc.queryForObject("SELECT is_deleted FROM ziper.product_boards WHERE id = ?", Boolean.class,
				deletedBoardId)).isTrue();
		assertThat(jdbc.queryForObject("SELECT id FROM ziper.product_board_card WHERE board_id = ? AND card_id = ?",
				Long.class, secondBoardId, cardId)).isEqualTo(originalSecondLinkId);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM ziper.product_board_card
				WHERE card_id = ? AND is_deleted = FALSE
				""", Integer.class, cardId)).isEqualTo(2);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM ziper.product_board_card
				WHERE card_id = ? AND board_id = ? AND is_deleted = TRUE
				""", Integer.class, cardId, firstBoardId)).isOne();
		assertThat(
				jdbc.queryForObject("SELECT is_deleted FROM ziper.product_cards WHERE id = ?", Boolean.class, cardId))
				.isFalse();
		assertThat(jdbc.queryForObject("SELECT rediscovery_stage FROM ziper.product_cards WHERE id = ?", Integer.class,
				cardId)).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT EXTRACT(EPOCH FROM (next_rediscovery_at - last_interacted_at))
				FROM ziper.product_cards WHERE id = ?
				""", Double.class, cardId)).isEqualTo(14d * 86400d);
	}

	@Test
	void 전체삭제카드에기존연결이있을때_새보드를선택하면_기존연결은비활성화된다() throws Exception {
		Long oldBoardId = insertBoard(userId, "이전 보드");
		Long targetBoardId = insertBoard(userId, "복구 보드");
		Long cardId = insertCard(userId, "full-delete", true, Instant.now().minus(1, ChronoUnit.DAYS));
		link(oldBoardId, cardId);

		mvc.perform(post(TRASH_PATH + "/restore", cardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content("{\"targetBoardIds\":[" + targetBoardId + "]}"))
				.andExpect(status().isNoContent());

		assertThat(jdbc.queryForObject("""
				SELECT is_deleted FROM ziper.product_board_card
				WHERE board_id = ? AND card_id = ?
				""", Boolean.class, oldBoardId, cardId)).isTrue();
		assertThat(jdbc.queryForObject("""
				SELECT is_deleted FROM ziper.product_board_card
				WHERE board_id = ? AND card_id = ?
				""", Boolean.class, targetBoardId, cardId)).isFalse();
	}

	@Test
	void 비어있거나타인보드를선택했을때_복구를요청하면_400또는404이고상품은유지된다() throws Exception {
		Long cardId = insertCard(userId, "invalid-target", true, Instant.now().minus(1, ChronoUnit.DAYS));
		mvc.perform(get(TRASH_PATH + "/restore-boards", cardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.boards").isEmpty());
		Long otherBoardId = insertBoard(otherUserId, "타인 보드");
		Long deletedBoardId = insertBoard(userId, "삭제 보드");
		jdbc.update("UPDATE ziper.product_boards SET is_deleted = TRUE, deleted_at = CURRENT_TIMESTAMP WHERE id = ?",
				deletedBoardId);

		mvc.perform(post(TRASH_PATH + "/restore", cardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content("{\"targetBoardIds\":[]}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post(TRASH_PATH + "/restore", cardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content("{\"targetBoardIds\":[" + otherBoardId + "]}"))
				.andExpect(status().isNotFound());
		mvc.perform(post(TRASH_PATH + "/restore", cardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content("{\"targetBoardIds\":[" + deletedBoardId + "]}"))
				.andExpect(status().isNotFound());
		assertThat(
				jdbc.queryForObject("SELECT is_deleted FROM ziper.product_cards WHERE id = ?", Boolean.class, cardId))
				.isTrue();
	}

	@Test
	void 만료되거나타인카드일때_복구보드조회와복구를요청하면_404를반환한다() throws Exception {
		Long boardId = insertBoard(userId, "복구 보드");
		Long expiredCardId = insertCard(userId, "expired", true, Instant.now().minus(31, ChronoUnit.DAYS));
		Long otherCardId = insertCard(otherUserId, "other", true, Instant.now().minus(1, ChronoUnit.DAYS));

		mvc.perform(get(TRASH_PATH + "/restore-boards", expiredCardId).header(HttpHeaders.AUTHORIZATION,
				bearer(accessToken))).andExpect(status().isNotFound());
		mvc.perform(post(TRASH_PATH + "/restore", expiredCardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content("{\"targetBoardIds\":[" + boardId + "]}"))
				.andExpect(status().isNotFound());
		mvc.perform(
				get(TRASH_PATH + "/restore-boards", otherCardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isNotFound());
		mvc.perform(get(TRASH_PATH + "/restore-boards", boardId)).andExpect(status().isUnauthorized());
	}

	private Long insertUser(String email, String nickname) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.users (email, nickname, status) VALUES (?, ?, 'ACTIVE') RETURNING id", Long.class,
				email, nickname);
	}

	private int boardProductCount(String path, Long boardId) throws Exception {
		String body = mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		for (JsonNode board : mapper.readTree(body).path("boards")) {
			if (board.path("boardId").asLong() == boardId.longValue()) {
				return board.path("productCount").asInt();
			}
		}
		throw new AssertionError("Board missing from response: " + boardId);
	}

	private Long insertBoard(Long ownerId, String name) {
		return jdbc.queryForObject("INSERT INTO ziper.product_boards (user_id, name) VALUES (?, ?) RETURNING id",
				Long.class, ownerId, name);
	}

	private Long insertCard(Long ownerId, String path, boolean deleted, Instant deletedAt) {
		return jdbc.queryForObject("""
				INSERT INTO ziper.product_cards (
				    user_id, product_url, product_title, price, image_url,
				    rediscovery_stage, is_deleted, deleted_at
				)
				VALUES (?, ?, '복구 상품', 1000, 'https://image.example/restore.jpg', 2, ?, ?)
				RETURNING id
				""", Long.class, ownerId, "https://shop.example/" + path, deleted,
				deletedAt == null ? null : Timestamp.from(deletedAt));
	}

	private void link(Long boardId, Long cardId) {
		jdbc.update("INSERT INTO ziper.product_board_card (board_id, card_id) VALUES (?, ?)", boardId, cardId);
	}

	private void clearDatabase() {
		jdbc.update("DELETE FROM ziper.image_mirror_jobs");
		jdbc.update("DELETE FROM ziper.product_board_card");
		jdbc.update("DELETE FROM ziper.product_cards");
		jdbc.update("DELETE FROM ziper.product_boards");
		jdbc.update("DELETE FROM ziper.users");
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}
}
