package com.ject.ziper.domain.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.List;

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
import com.ject.ziper.domain.tag.entity.Tag;
import com.ject.ziper.domain.tag.repository.TagRepository;
import com.ject.ziper.domain.tag.service.TagService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TagIntegrationTest extends PostgresTestSupport {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private TagService tagService;

	@Autowired
	private TagRepository tagRepository;

	@BeforeEach
	void cleanUp() {
		jdbc.update("DELETE FROM ziper.product_card_tags");
		jdbc.update("DELETE FROM ziper.product_cards");
		jdbc.update("DELETE FROM ziper.tags WHERE user_id IS NOT NULL");
		jdbc.update("DELETE FROM ziper.users");
	}

	@Test
	void V8마이그레이션이적용된DB_기본시드와소유권제약조건을검증하면_기본4종보존과유저별부분유니크가동작한다() {
		assertThat(jdbc.queryForList(
				"SELECT title FROM ziper.tags WHERE is_default = TRUE AND user_id IS NULL ORDER BY id", String.class))
				.containsExactly("봄", "여름", "가을", "겨울");

		Long firstUserId = insertUser("tag-v8-a@ziper.test", "태그유저A");
		Long secondUserId = insertUser("tag-v8-b@ziper.test", "태그유저B");

		// 기본 태그는 user_id가 NULL이어야 하고, 커스텀 태그는 user_id가 필수다.
		assertThatThrownBy(() -> jdbc
				.update("INSERT INTO ziper.tags(user_id, title, is_default) VALUES (?, '오류기본', TRUE)", firstUserId))
				.hasRootCauseInstanceOf(SQLException.class);
		assertThatThrownBy(
				() -> jdbc.update("INSERT INTO ziper.tags(user_id, title, is_default) VALUES (NULL, '고아커스텀', FALSE)"))
				.hasRootCauseInstanceOf(SQLException.class);

		// 기본 태그 제목 중복 금지 (uq_tags_default_title)
		assertThatThrownBy(
				() -> jdbc.update("INSERT INTO ziper.tags(user_id, title, is_default) VALUES (NULL, '봄', TRUE)"))
				.hasRootCauseInstanceOf(SQLException.class);

		// 서로 다른 유저는 동일한 커스텀 태그명을 저장할 수 있다.
		assertThat(
				jdbc.update("INSERT INTO ziper.tags(user_id, title, is_default) VALUES (?, '출근룩', FALSE)", firstUserId))
				.isOne();
		assertThat(jdbc.update("INSERT INTO ziper.tags(user_id, title, is_default) VALUES (?, '출근룩', FALSE)",
				secondUserId)).isOne();

		// 동일 유저의 커스텀 태그명 중복 금지 (uq_tags_user_title)
		assertThatThrownBy(() -> jdbc
				.update("INSERT INTO ziper.tags(user_id, title, is_default) VALUES (?, '출근룩', FALSE)", firstUserId))
				.hasRootCauseInstanceOf(SQLException.class);
	}

	@Test
	void 인증없는요청_해시태그API를호출하면_401을반환한다() throws Exception {
		mvc.perform(get("/api/v1/tags")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/tags").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"데이트\"}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/v1/tags/1")).andExpect(status().isUnauthorized());
	}

	@Test
	void 기본태그와다중유저커스텀태그_목록을조회하면_기본4종우선및본인생성순으로정렬되어반환된다() throws Exception {
		Long userA = insertUser("list-a@ziper.test", "목록유저A");
		Long userB = insertUser("list-b@ziper.test", "목록유저B");
		String tokenA = bearerToken(userA);
		String tokenB = bearerToken(userB);

		mvc.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, tokenA)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"데이트\"}"))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, tokenB)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"타인태그\"}"))
				.andExpect(status().isCreated());
		mvc.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, tokenA)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"출근룩\"}"))
				.andExpect(status().isCreated());

		mvc.perform(get("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isOk())
				.andExpect(jsonPath("$.tags.length()").value(6)).andExpect(jsonPath("$.tags[0].title").value("봄"))
				.andExpect(jsonPath("$.tags[0].isDefault").value(true))
				.andExpect(jsonPath("$.tags[1].title").value("여름"))
				.andExpect(jsonPath("$.tags[1].isDefault").value(true))
				.andExpect(jsonPath("$.tags[2].title").value("가을"))
				.andExpect(jsonPath("$.tags[2].isDefault").value(true))
				.andExpect(jsonPath("$.tags[3].title").value("겨울"))
				.andExpect(jsonPath("$.tags[3].isDefault").value(true))
				.andExpect(jsonPath("$.tags[4].title").value("데이트"))
				.andExpect(jsonPath("$.tags[4].isDefault").value(false))
				.andExpect(jsonPath("$.tags[5].title").value("출근룩"))
				.andExpect(jsonPath("$.tags[5].isDefault").value(false));
	}

	@Test
	void 해시태그생성요청_정규화와글자수및중복규칙을검증하면_유효값만201로저장하고타유저동일명은허용한다() throws Exception {
		Long userA = insertUser("create-a@ziper.test", "생성유저A");
		Long userB = insertUser("create-b@ziper.test", "생성유저B");
		String tokenA = bearerToken(userA);
		String tokenB = bearerToken(userB);

		// 선행 # 및 앞뒤 공백 정규화 + 최대 6자 허용
		mvc.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, tokenA)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" ##데이트 \"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.tagId").isNumber())
				.andExpect(jsonPath("$.title").value("데이트")).andExpect(jsonPath("$.isDefault").value(false));

		mvc.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, tokenA)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"#가나다라마바\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("가나다라마바"))
				.andExpect(jsonPath("$.isDefault").value(false));

		// 공백, #만 입력, 7자 초과는 400 INVALID_TAG_TITLE
		for (String invalidBody : List.of("{}", "{\"title\":null}", "{\"title\":\"\"}", "{\"title\":\"   \"}",
				"{\"title\":\"###\"}", "{\"title\":\" # \"}", "{\"title\":\"가나다라마바사\"}")) {
			mvc.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, tokenA)
					.contentType(MediaType.APPLICATION_JSON).content(invalidBody)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_TAG_TITLE"));
		}

		// 기본 4종 및 본인 커스텀 태그와 중복은 409 DUPLICATE_TAG
		for (String duplicateTitle : List.of("봄", "##봄", " #겨울 ", "데이트", "#데이트")) {
			mvc.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, tokenA)
					.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + duplicateTitle + "\"}"))
					.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_TAG"));
		}

		// 다른 유저는 동일한 커스텀 태그명을 생성할 수 있다.
		mvc.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, tokenB)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"#데이트\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("데이트"))
				.andExpect(jsonPath("$.isDefault").value(false));
	}

	@Test
	void 기본태그와타인태그및미존재리소스_삭제를요청하면_403또는404로차단한다() throws Exception {
		Long userA = insertUser("del-err-a@ziper.test", "삭제오류A");
		Long userB = insertUser("del-err-b@ziper.test", "삭제오류B");
		String tokenA = bearerToken(userA);
		String tokenB = bearerToken(userB);

		Long defaultTagId = jdbc.queryForObject("SELECT id FROM ziper.tags WHERE title = '봄' AND is_default = TRUE",
				Long.class);
		Long userATagId = createCustomTagViaApi(tokenA, "전용태그");
		Long userBCardId = insertCard(userB, "https://store.example/items/b-1", "타인 상품");

		// 기본 태그 삭제 시도 -> 403 DEFAULT_TAG_DELETE_FORBIDDEN
		mvc.perform(delete("/api/v1/tags/{tagId}", defaultTagId).header(HttpHeaders.AUTHORIZATION, tokenA))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DEFAULT_TAG_DELETE_FORBIDDEN"));
		assertThat(tagRepository.findById(defaultTagId)).isPresent();

		// 미존재 태그 삭제 시도 -> 404 TAG_NOT_FOUND
		mvc.perform(delete("/api/v1/tags/{tagId}", 999999L).header(HttpHeaders.AUTHORIZATION, tokenA))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TAG_NOT_FOUND"));

		// 타인 커스텀 태그 삭제 시도 -> 404 TAG_NOT_FOUND (존재 여부 노출 금지)
		mvc.perform(delete("/api/v1/tags/{tagId}", userATagId).header(HttpHeaders.AUTHORIZATION, tokenB))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TAG_NOT_FOUND"));
		assertThat(tagRepository.findById(userATagId)).isPresent();

		// 미존재 카드 또는 타인 카드 ID 지정 -> 404 NOT_FOUND, 태그 유지
		mvc.perform(delete("/api/v1/tags/{tagId}", userATagId).param("cardId", "999999")
				.header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(delete("/api/v1/tags/{tagId}", userATagId).param("cardId", String.valueOf(userBCardId))
				.header(HttpHeaders.AUTHORIZATION, tokenA)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		assertThat(tagRepository.findById(userATagId)).isPresent();
	}

	@Test
	void 두카드에공유된커스텀태그_특정카드ID로삭제하면_해당카드연결만해제하고태그와다른카드연결은유지한다() throws Exception {
		Long userId = insertUser("shared-card@ziper.test", "공유태그유저");
		String token = bearerToken(userId);
		Long tagId = createCustomTagViaApi(token, "데이트");
		Long card1 = insertCard(userId, "https://store.example/items/shared-1", "원피스");
		Long card2 = insertCard(userId, "https://store.example/items/shared-2", "가디건");
		linkCardTag(card1, tagId);
		linkCardTag(card2, tagId);

		mvc.perform(delete("/api/v1/tags/{tagId}", tagId).param("cardId", String.valueOf(card1))
				.header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isNoContent());

		assertThat(tagRepository.findById(tagId)).isPresent();
		assertThat(countCardTagLink(card1, tagId)).isZero();
		assertThat(countCardTagLink(card2, tagId)).isOne();
	}

	@Test
	void 단일카드에만연결된커스텀태그_해당카드ID로삭제하면_연결이연쇄삭제되고태그자체도삭제된다() throws Exception {
		Long userId = insertUser("exclusive-card@ziper.test", "단독태그유저");
		String token = bearerToken(userId);
		Long tagId = createCustomTagViaApi(token, "단독태그");
		Long card1 = insertCard(userId, "https://store.example/items/exclusive-1", "코트");
		linkCardTag(card1, tagId);

		mvc.perform(delete("/api/v1/tags/{tagId}", tagId).param("cardId", String.valueOf(card1))
				.header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isNoContent());

		assertThat(tagRepository.findById(tagId)).isEmpty();
		assertThat(countCardTagLink(card1, tagId)).isZero();
	}

	@Test
	void 여러카드에연결된커스텀태그_카드ID없이전역삭제하면_모든카드연결이연쇄삭제되고태그가제거된다() throws Exception {
		Long userId = insertUser("global-del@ziper.test", "전역삭제유저");
		String token = bearerToken(userId);
		Long tagId = createCustomTagViaApi(token, "여행룩");
		Long card1 = insertCard(userId, "https://store.example/items/global-1", "선글라스");
		Long card2 = insertCard(userId, "https://store.example/items/global-2", "모자");
		linkCardTag(card1, tagId);
		linkCardTag(card2, tagId);

		mvc.perform(delete("/api/v1/tags/{tagId}", tagId).header(HttpHeaders.AUTHORIZATION, token))
				.andExpect(status().isNoContent());

		assertThat(tagRepository.findById(tagId)).isEmpty();
		assertThat(countCardTagLink(card1, tagId)).isZero();
		assertThat(countCardTagLink(card2, tagId)).isZero();
	}

	@Test
	void 연결된적없는커스텀태그_cardId로삭제하면_204를반환하고태그는유지된다() throws Exception {
		Long userId = insertUser("unlinked@ziper.test", "미연결유저");
		String token = bearerToken(userId);
		Long tagId = createCustomTagViaApi(token, "미연결");
		Long linkedCard = insertCard(userId, "https://store.example/items/unlinked-1", "상의");
		Long otherCard = insertCard(userId, "https://store.example/items/unlinked-2", "하의");
		linkCardTag(linkedCard, tagId);

		mvc.perform(delete("/api/v1/tags/{tagId}", tagId).param("cardId", String.valueOf(otherCard))
				.header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isNoContent());

		assertThat(tagRepository.findById(tagId)).isPresent();
		assertThat(countCardTagLink(linkedCard, tagId)).isOne();
	}

	@Test
	void 휴지통카드ID로_태그삭제를요청하면_404를반환하고태그는유지된다() throws Exception {
		Long userId = insertUser("trash-card@ziper.test", "휴지통유저");
		String token = bearerToken(userId);
		Long tagId = createCustomTagViaApi(token, "휴지통태그");
		Long cardId = insertCard(userId, "https://store.example/items/trash-1", "휴지통 상품");
		linkCardTag(cardId, tagId);
		jdbc.update("UPDATE ziper.product_cards SET is_deleted = TRUE, deleted_at = CURRENT_TIMESTAMP WHERE id = ?",
				cardId);

		mvc.perform(delete("/api/v1/tags/{tagId}", tagId).param("cardId", String.valueOf(cardId))
				.header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));

		assertThat(tagRepository.findById(tagId)).isPresent();
		assertThat(countCardTagLink(cardId, tagId)).isOne();
	}

	@Test
	void 선행공백과샵이섞인입력과분해형한글_태그를생성하면_정리후중복은409로판정한다() throws Exception {
		Long userId = insertUser("normalize@ziper.test", "정규화유저");
		String token = bearerToken(userId);

		mvc.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  # #\uBD04x\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("\uBD04x"));
		mvc.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" #  \uC5EC\uB984\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_TAG"));
		mvc.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\u1112\u1161\u11AB\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("\uD55C"));
		mvc.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\uD55C\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_TAG"));
		mvc.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, token)
				.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\u200B\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TAG_TITLE"));
	}

	@Test
	void 카드저장용태그목록_resolveOrCreateForCard를호출하면_정규화중복제거후기존태그재사용및신규커스텀태그를순서대로반환한다() {
		Long userA = insertUser("resolve-a@ziper.test", "헬퍼유저A");
		Long userB = insertUser("resolve-b@ziper.test", "헬퍼유저B");
		Long existingCustomId = tagService.createTag(userA, "데이트").tagId();

		List<Tag> resolvedA = tagService.resolveOrCreateForCard(userA, List.of(" #봄 ", "봄", "#데이트", " #출근룩 ", "출근룩"));

		assertThat(resolvedA).extracting(Tag::getTitle).containsExactly("봄", "데이트", "출근룩");
		assertThat(resolvedA.get(0).isDefault()).isTrue();
		assertThat(resolvedA.get(1).getId()).isEqualTo(existingCustomId);
		assertThat(resolvedA.get(2).isDefault()).isFalse();
		assertThat(resolvedA.get(2).getUserId()).isEqualTo(userA);

		// 다른 유저는 기본 태그는 공유하고 커스텀 태그는 독립 생성된다.
		List<Tag> resolvedB = tagService.resolveOrCreateForCard(userB, List.of("봄", "출근룩"));
		assertThat(resolvedB.get(0).getId()).isEqualTo(resolvedA.get(0).getId());
		assertThat(resolvedB.get(1).getId()).isNotEqualTo(resolvedA.get(2).getId());
		assertThat(resolvedB.get(1).getUserId()).isEqualTo(userB);

		// 빈 목록 또는 잘못된 태그 포함 시 400 INVALID_TAG_TITLE
		assertThatThrownBy(() -> tagService.resolveOrCreateForCard(userA, List.of()))
				.isInstanceOf(BusinessException.class).extracting(error -> ((BusinessException) error).errorCode())
				.isEqualTo(ErrorCode.INVALID_TAG_TITLE);
		assertThatThrownBy(() -> tagService.resolveOrCreateForCard(userA, List.of("신규정상", "일곱글자초과임")))
				.isInstanceOf(BusinessException.class).extracting(error -> ((BusinessException) error).errorCode())
				.isEqualTo(ErrorCode.INVALID_TAG_TITLE);
		// 잘못된 태그가 포함된 호출은 트랜잭션 롤백되어 신규 태그가 저장되지 않는다.
		assertThat(tagRepository.existsAccessibleTitle(userA, "신규정상")).isFalse();
	}

	private Long insertUser(String email, String nickname) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.users(email, nickname, status) VALUES (?, ?, 'ACTIVE') RETURNING id", Long.class,
				email, nickname);
	}

	private Long insertCard(Long userId, String productUrl, String productTitle) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.product_cards(user_id, product_url, product_title, price, fallback_icon) VALUES (?, ?, ?, 19900, 'TOP') RETURNING id",
				Long.class, userId, productUrl, productTitle);
	}

	private void linkCardTag(Long cardId, Long tagId) {
		jdbc.update("INSERT INTO ziper.product_card_tags(card_id, tag_id) VALUES (?, ?)", cardId, tagId);
	}

	private int countCardTagLink(Long cardId, Long tagId) {
		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM ziper.product_card_tags WHERE card_id = ? AND tag_id = ?", Integer.class, cardId,
				tagId);
		return count == null ? 0 : count;
	}

	private String bearerToken(Long userId) {
		return "Bearer " + jwtTokenProvider.issueAccessToken(userId);
	}

	private Long createCustomTagViaApi(String bearerToken, String title) throws Exception {
		String body = mvc
				.perform(post("/api/v1/tags").header(HttpHeaders.AUTHORIZATION, bearerToken)
						.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		JsonNode node = objectMapper.readTree(body);
		return node.get("tagId").asLong();
	}
}
