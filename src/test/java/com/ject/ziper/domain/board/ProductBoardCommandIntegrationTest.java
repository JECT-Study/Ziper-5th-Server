package com.ject.ziper.domain.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.domain.board.entity.ProductBoard;
import com.ject.ziper.domain.board.repository.ProductBoardRepository;
import com.ject.ziper.domain.board.service.ProductBoardCommandService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProductBoardCommandIntegrationTest extends PostgresTestSupport {
	private static final String BOARDS_PATH = "/api/v1/boards";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ProductBoardRepository productBoardRepository;

	@Autowired
	private ProductBoardCommandService boardCommandService;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	private Long userId;
	private Long otherUserId;
	private Long defaultBoardId;
	private String accessToken;
	private String otherAccessToken;

	@BeforeEach
	void setUp() {
		clearDatabase();
		userId = insertUser("board-owner@example.com", "보드오너");
		otherUserId = insertUser("board-other@example.com", "다른유저");
		defaultBoardId = productBoardRepository.save(ProductBoard.createDefault(userId)).getId();
		productBoardRepository.save(ProductBoard.createDefault(otherUserId));
		accessToken = jwtTokenProvider.issueAccessToken(userId);
		otherAccessToken = jwtTokenProvider.issueAccessToken(otherUserId);
	}

	@AfterEach
	void tearDown() {
		clearDatabase();
	}

	@Test
	void V9마이그레이션이적용된DB_기본보드와인덱스를확인하면_생성시각과활성정렬인덱스가정상동작한다() {
		ProductBoard savedDefault = productBoardRepository.findById(defaultBoardId).orElseThrow();
		assertThat(savedDefault.getName()).isEqualTo(ProductBoard.DEFAULT_BOARD_NAME);
		assertThat(savedDefault.isModifiable()).isFalse();
		assertThat(savedDefault.isDeleted()).isFalse();
		assertThat(savedDefault.getDeletedAt()).isNull();
		assertThat(savedDefault.getCreatedAt()).isNotNull();

		Long legacyInsertedId = jdbc.queryForObject(
				"INSERT INTO ziper.product_boards (user_id, name, modifiable) VALUES (?, ?, TRUE) RETURNING id",
				Long.class, userId, "레거시보드");
		OffsetDateTime createdAt = jdbc.queryForObject("SELECT created_at FROM ziper.product_boards WHERE id = ?",
				OffsetDateTime.class, legacyInsertedId);
		assertThat(createdAt).isNotNull();

		Integer indexCount = jdbc.queryForObject(
				"SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'ziper' AND tablename = 'product_boards' "
						+ "AND indexname = 'idx_product_boards_user_created_at'",
				Integer.class);
		assertThat(indexCount).isOne();
	}

	@Test
	void 인증토큰이없는요청_보드생성과수정과삭제를호출하면_401을반환한다() throws Exception {
		mvc.perform(post(BOARDS_PATH).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"봄 코디\"}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(patch(BOARDS_PATH + "/" + defaultBoardId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"봄 코디\"}")).andExpect(status().isUnauthorized());
		mvc.perform(delete(BOARDS_PATH + "/" + defaultBoardId)).andExpect(status().isUnauthorized());
	}

	@Test
	void 앞뒤공백이포함된유효한이름_보드를생성하면_201과공백제거된커스텀보드를반환한다() throws Exception {
		MvcResult result = mvc
				.perform(post(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
						.contentType(MediaType.APPLICATION_JSON).content(nameBody("  봄 아우터  ")))
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.boardId").isNumber()).andExpect(jsonPath("$.name").value("봄 아우터"))
				.andExpect(jsonPath("$.modifiable").value(true)).andExpect(jsonPath("$.productCount").value(0))
				.andExpect(jsonPath("$.createdAt").isNotEmpty()).andReturn();

		Long createdId = objectMapper.readTree(result.getResponse().getContentAsString()).get("boardId").asLong();
		ProductBoard saved = productBoardRepository.findById(createdId).orElseThrow();
		assertThat(saved.getUserId()).isEqualTo(userId);
		assertThat(saved.getName()).isEqualTo("봄 아우터");
		assertThat(saved.isModifiable()).isTrue();
		assertThat(saved.isDeleted()).isFalse();
		assertThat(saved.getCreatedAt()).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"봄", "가나다라마바사아자차", "가나다라 마바사아자", "  12345 7890  "})
	void 공백포함1자에서10자경계이름_보드를생성하면_201을반환한다(String name) throws Exception {
		mvc.perform(post(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody(name))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.name").value(name.strip())).andExpect(jsonPath("$.modifiable").value(true))
				.andExpect(jsonPath("$.productCount").value(0));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "\t\n", "가나다라마바사아자차카", "가나다라 마바사아자차", "12345678901"})
	void 공백이거나10자를초과한이름_보드를생성하면_400과보드이름오류코드를반환한다(String invalidName) throws Exception {
		mvc.perform(post(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody(invalidName)))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("INVALID_BOARD_NAME"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"\u00a0", "\u200b", "\u00a0\u200b\u3000 ", "\ufeff", "\u2060\u200d"})
	void 유니코드공백이나서식문자만있는이름_보드를생성하면_400과보드이름오류코드를반환한다(String invalidName) throws Exception {
		mvc.perform(post(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody(invalidName)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BOARD_NAME"));
	}

	@Test
	void 앞뒤에줄바꿈방지공백과제로폭공백이붙은이름_보드를생성하면_201과제거된이름을반환한다() throws Exception {
		mvc.perform(post(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody("\u00a0\u200b봄 코디\u200b\u00a0")))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("봄 코디"));
	}

	@Test
	void 이름필드가누락된요청_보드를생성하면_400과보드이름오류코드를반환한다() throws Exception {
		mvc.perform(post(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_BOARD_NAME"));
	}

	@Test
	void 동일사용자의활성보드와중복된이름_보드를생성하면_409를반환하고타사용자는생성에성공한다() throws Exception {
		mvc.perform(post(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody("출근룩"))).andExpect(status().isCreated());

		mvc.perform(post(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody("  출근룩  "))).andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("DUPLICATE_BOARD_NAME"));

		mvc.perform(post(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody(ProductBoard.DEFAULT_BOARD_NAME)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_BOARD_NAME"));

		mvc.perform(post(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(otherAccessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody("출근룩"))).andExpect(status().isCreated());
	}

	@Test
	void 이미삭제한커스텀보드와같은이름_다시생성하면_201로재사용할수있다() throws Exception {
		Long deletedBoardId = createBoardViaApi(accessToken, "여름 원피스");

		mvc.perform(delete(BOARDS_PATH + "/" + deletedBoardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isNoContent());

		MvcResult recreated = mvc
				.perform(post(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
						.contentType(MediaType.APPLICATION_JSON).content(nameBody("여름 원피스")))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("여름 원피스")).andReturn();

		Long recreatedId = objectMapper.readTree(recreated.getResponse().getContentAsString()).get("boardId").asLong();
		assertThat(recreatedId).isNotEqualTo(deletedBoardId);
	}

	@Test
	void 소유한커스텀보드_새이름과동일이름으로수정하면_200으로갱신및멱등처리한다() throws Exception {
		Long boardId = createBoardViaApi(accessToken, "가을 코디");

		mvc.perform(patch(BOARDS_PATH + "/" + boardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody("  겨울 아우터  "))).andExpect(status().isOk())
				.andExpect(jsonPath("$.boardId").value(boardId)).andExpect(jsonPath("$.name").value("겨울 아우터"));

		assertThat(productBoardRepository.findById(boardId).orElseThrow().getName()).isEqualTo("겨울 아우터");

		mvc.perform(patch(BOARDS_PATH + "/" + boardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody("겨울 아우터"))).andExpect(status().isOk())
				.andExpect(jsonPath("$.boardId").value(boardId)).andExpect(jsonPath("$.name").value("겨울 아우터"));
	}

	@Test
	void 기본보드_이름수정을요청하면_403과수정불가오류코드를반환한다() throws Exception {
		mvc.perform(patch(BOARDS_PATH + "/" + defaultBoardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody("새 기본"))).andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("DEFAULT_BOARD_NOT_MODIFIABLE"));
	}

	@Test
	void 타인보드나미존재보드나삭제된보드_이름수정을요청하면_404를반환한다() throws Exception {
		Long otherBoardId = createBoardViaApi(otherAccessToken, "타인 보드");
		Long deletedBoardId = createBoardViaApi(accessToken, "삭제될 보드");
		mvc.perform(delete(BOARDS_PATH + "/" + deletedBoardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isNoContent());

		for (Long targetId : List.of(otherBoardId, 999_999L, deletedBoardId)) {
			mvc.perform(patch(BOARDS_PATH + "/" + targetId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
					.contentType(MediaType.APPLICATION_JSON).content(nameBody("새 이름"))).andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));
		}
	}

	@Test
	void 다른활성보드와중복되거나잘못된이름_이름수정을요청하면_409또는400을반환한다() throws Exception {
		Long boardId = createBoardViaApi(accessToken, "보드 하나");
		createBoardViaApi(accessToken, "보드 둘");

		mvc.perform(patch(BOARDS_PATH + "/" + boardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody("보드 둘"))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_BOARD_NAME"));

		mvc.perform(patch(BOARDS_PATH + "/" + boardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody(ProductBoard.DEFAULT_BOARD_NAME)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_BOARD_NAME"));

		mvc.perform(patch(BOARDS_PATH + "/" + boardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody("열한글자보드이름입니다")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BOARD_NAME"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"\u00a0", "\u200b", "\u00a0\u200b "})
	void 유니코드공백이나서식문자만있는이름_이름수정을요청하면_400과보드이름오류코드를반환한다(String invalidName) throws Exception {
		Long boardId = createBoardViaApi(accessToken, "보드 하나");

		mvc.perform(patch(BOARDS_PATH + "/" + boardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody(invalidName)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BOARD_NAME"));
		assertThat(productBoardRepository.findById(boardId).orElseThrow().getName()).isEqualTo("보드 하나");
	}

	@ParameterizedTest
	@ValueSource(strings = {"가\u0000나", "가\u0007"})
	void 제어문자가포함된이름으로_보드생성과수정을요청하면_공통400오류를반환한다(String name) throws Exception {
		Long boardId = createBoardViaApi(accessToken, "보드 하나");
		mvc.perform(post(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody(name))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mvc.perform(patch(BOARDS_PATH + "/" + boardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(nameBody(name))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		assertThat(productBoardRepository.findById(boardId).orElseThrow().getName()).isEqualTo("보드 하나");
	}

	@Test
	void 기본보드나타인보드나삭제된보드_삭제를요청하면_403또는404를반환한다() throws Exception {
		mvc.perform(delete(BOARDS_PATH + "/" + defaultBoardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DEFAULT_BOARD_NOT_MODIFIABLE"));

		Long otherBoardId = createBoardViaApi(otherAccessToken, "타인 커스텀");
		mvc.perform(delete(BOARDS_PATH + "/" + otherBoardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));

		mvc.perform(delete(BOARDS_PATH + "/999999").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));

		Long emptyBoardId = createBoardViaApi(accessToken, "빈 보드");
		mvc.perform(delete(BOARDS_PATH + "/" + emptyBoardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isNoContent());
		mvc.perform(delete(BOARDS_PATH + "/" + emptyBoardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));
	}

	@Test
	void 단독상품과다중소속상품이연결된보드_삭제하면_고아상품만휴지통으로이동하고다중소속상품은유지한다() throws Exception {
		Long targetBoardId = createBoardViaApi(accessToken, "삭제 대상");
		Long activeOtherBoardId = createBoardViaApi(accessToken, "유지 보드");
		Long alreadyDeletedBoardId = createBoardViaApi(accessToken, "이미 삭제");
		jdbc.update("UPDATE ziper.product_boards SET is_deleted = TRUE, deleted_at = ? WHERE id = ?",
				Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS)), alreadyDeletedBoardId);

		Long orphanCardId = insertCard(userId, "https://shop.example/orphan", "고아 상품", false, null);
		Long sharedCardId = insertCard(userId, "https://shop.example/shared", "다중 소속 상품", false, null);
		Long orphanWithDeletedBoardCardId = insertCard(userId, "https://shop.example/deleted-sibling", "삭제보드만남은상품",
				false, null);
		Long unrelatedActiveCardId = insertCard(userId, "https://shop.example/unrelated", "다른 보드 전용 상품", false, null);
		Instant previousDeletedAt = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
		Long alreadyTrashedCardId = insertCard(userId, "https://shop.example/already-trashed", "이미 휴지통 상품", true,
				previousDeletedAt);

		linkCardToBoard(targetBoardId, orphanCardId);
		linkCardToBoard(targetBoardId, sharedCardId);
		linkCardToBoard(activeOtherBoardId, sharedCardId);
		linkCardToBoard(targetBoardId, orphanWithDeletedBoardCardId);
		linkCardToBoard(alreadyDeletedBoardId, orphanWithDeletedBoardCardId);
		linkCardToBoard(activeOtherBoardId, unrelatedActiveCardId);
		linkCardToBoard(targetBoardId, alreadyTrashedCardId);

		mvc.perform(delete(BOARDS_PATH + "/" + targetBoardId).header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
				.andExpect(status().isNoContent());

		ProductBoard deletedBoard = productBoardRepository.findById(targetBoardId).orElseThrow();
		assertThat(deletedBoard.isDeleted()).isTrue();
		assertThat(deletedBoard.getDeletedAt()).isNotNull();

		Integer remainingTargetLinks = jdbc.queryForObject(
				"SELECT COUNT(*) FROM ziper.product_board_card WHERE board_id = ?", Integer.class, targetBoardId);
		assertThat(remainingTargetLinks).isZero();

		List<Long> remainingActiveBoardCards = jdbc.queryForList(
				"SELECT card_id FROM ziper.product_board_card WHERE board_id = ? ORDER BY card_id", Long.class,
				activeOtherBoardId);
		assertThat(remainingActiveBoardCards).containsExactly(sharedCardId, unrelatedActiveCardId);

		CardDeletionState orphanState = readCardState(orphanCardId);
		assertThat(orphanState.deleted()).isTrue();
		assertThat(orphanState.deletedAt()).isNotNull();
		assertThat(orphanState.updatedAt()).isEqualTo(orphanState.deletedAt());

		CardDeletionState sharedState = readCardState(sharedCardId);
		assertThat(sharedState.deleted()).isFalse();
		assertThat(sharedState.deletedAt()).isNull();

		CardDeletionState deletedSiblingState = readCardState(orphanWithDeletedBoardCardId);
		assertThat(deletedSiblingState.deleted()).isTrue();
		assertThat(deletedSiblingState.deletedAt()).isNotNull();

		CardDeletionState unrelatedState = readCardState(unrelatedActiveCardId);
		assertThat(unrelatedState.deleted()).isFalse();
		assertThat(unrelatedState.deletedAt()).isNull();

		CardDeletionState alreadyTrashedState = readCardState(alreadyTrashedCardId);
		assertThat(alreadyTrashedState.deleted()).isTrue();
		assertThat(alreadyTrashedState.deletedAt()).isEqualTo(previousDeletedAt);
	}

	@Test
	void 같은이름보드생성이동시에요청될때_병렬실행하면_하나만성공하고나머지는중복오류를반환한다() throws Exception {
		int concurrency = 4;
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		try {
			List<Callable<String>> tasks = new ArrayList<>();
			for (int i = 0; i < concurrency; i++) {
				tasks.add(() -> {
					try {
						boardCommandService.createBoard(userId, "동시 보드");
						return "CREATED";
					} catch (BusinessException exception) {
						return exception.errorCode().name();
					}
				});
			}
			List<Future<String>> futures = executor.invokeAll(tasks);
			List<String> outcomes = new ArrayList<>();
			for (Future<String> future : futures) {
				outcomes.add(future.get());
			}
			assertThat(outcomes).filteredOn("CREATED"::equals).hasSize(1);
			assertThat(outcomes).filteredOn(ErrorCode.DUPLICATE_BOARD_NAME.name()::equals).hasSize(concurrency - 1);
		} finally {
			executor.shutdownNow();
		}
	}

	private Long createBoardViaApi(String token, String name) throws Exception {
		MvcResult result = mvc
				.perform(post(BOARDS_PATH).header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(nameBody(name)))
				.andExpect(status().isCreated()).andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString()).get("boardId").asLong();
	}

	private String nameBody(String name) throws Exception {
		return objectMapper.writeValueAsString(Map.of("name", name));
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private Long insertUser(String email, String nickname) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.users (email, nickname, status) VALUES (?, ?, 'ACTIVE') RETURNING id", Long.class,
				email, nickname);
	}

	private Long insertCard(Long ownerId, String url, String title, boolean deleted, Instant deletedAt) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.product_cards (user_id, product_url, product_title, price, fallback_icon, is_deleted, deleted_at) "
						+ "VALUES (?, ?, ?, 19900, 'TOP', ?, ?) RETURNING id",
				Long.class, ownerId, url, title, deleted, deletedAt == null ? null : Timestamp.from(deletedAt));
	}

	private void linkCardToBoard(Long boardId, Long cardId) {
		jdbc.update("INSERT INTO ziper.product_board_card (board_id, card_id) VALUES (?, ?)", boardId, cardId);
	}

	private CardDeletionState readCardState(Long cardId) {
		return jdbc.queryForObject("SELECT is_deleted, deleted_at, updated_at FROM ziper.product_cards WHERE id = ?",
				(rs, rowNum) -> {
					OffsetDateTime deletedAt = rs.getObject("deleted_at", OffsetDateTime.class);
					OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
					return new CardDeletionState(rs.getBoolean("is_deleted"),
							deletedAt == null ? null : deletedAt.toInstant(),
							updatedAt == null ? null : updatedAt.toInstant());
				}, cardId);
	}

	private void clearDatabase() {
		jdbc.update("DELETE FROM ziper.product_board_card");
		jdbc.update("DELETE FROM ziper.product_cards");
		jdbc.update("DELETE FROM ziper.product_boards");
		jdbc.update("DELETE FROM ziper.users");
	}

	private record CardDeletionState(boolean deleted, Instant deletedAt, Instant updatedAt) {
	}
}
