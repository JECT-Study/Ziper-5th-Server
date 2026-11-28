package com.ject.ziper.domain.trash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;
import com.ject.ziper.global.storage.ObjectStorageClient;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TrashQueryIntegrationTest extends PostgresTestSupport {
	private static final String PATH = "/api/v1/trash/products";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private JwtTokenProvider tokens;

	@MockitoSpyBean
	private ObjectStorageClient storage;

	private Long userId;
	private Long otherUserId;
	private String accessToken;

	@BeforeEach
	void setUp() {
		clearDatabase();
		userId = insertUser("trash-owner@example.com", "휴지통주인");
		otherUserId = insertUser("trash-other@example.com", "다른사용자");
		accessToken = tokens.issueAccessToken(userId);
	}

	@AfterEach
	void tearDown() {
		clearDatabase();
	}

	@Test
	void 인증토큰이없을때_휴지통목록을조회하면_401을반환한다() throws Exception {
		mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
	}

	@Test
	void 삭제된상품이없을때_휴지통목록을조회하면_빈항목배열을반환한다() throws Exception {
		mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.totalCount").doesNotExist());
	}

	@Test
	void 보관기간과소유자가다른카드가섞였을때_휴지통목록을조회하면_미만료된본인카드만삭제최신순으로반환한다() throws Exception {
		Instant now = Instant.now();
		Long newest = insertCard(userId, "최신 상품", "최신몰", 19000L, "https://image.example/new.jpg", null, true,
				now.minus(1, ChronoUnit.HOURS));
		Long older = insertCard(userId, "오래된 상품", "오래된몰", 29000L, "https://image.example/old.jpg", null, true,
				now.minus(29, ChronoUnit.DAYS).minus(23, ChronoUnit.HOURS));
		insertCard(userId, "기한 만료", "만료몰", 3000L, "https://image.example/expired.jpg", null, true,
				now.minus(30, ChronoUnit.DAYS).minus(1, ChronoUnit.MINUTES));
		insertCard(userId, "활성 상품", "활성몰", 4000L, "https://image.example/active.jpg", null, false, null);
		insertCard(otherUserId, "타인 상품", "타인몰", 5000L, "https://image.example/other.jpg", null, true,
				now.minus(1, ChronoUnit.HOURS));

		mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.items[0].cardId").value(newest))
				.andExpect(jsonPath("$.items[0].shop").value("최신몰"))
				.andExpect(jsonPath("$.items[0].productTitle").value("최신 상품"))
				.andExpect(jsonPath("$.items[0].price").value(19000))
				.andExpect(jsonPath("$.items[0].imageUrl").value("https://image.example/new.jpg"))
				.andExpect(jsonPath("$.items[0].daysRemaining").value(30))
				.andExpect(jsonPath("$.items[1].cardId").value(older))
				.andExpect(jsonPath("$.items[1].daysRemaining").value(1))
				.andExpect(jsonPath("$.items[0].deletedAt").doesNotExist())
				.andExpect(jsonPath("$.items[0].previousBoardIds").doesNotExist())
				.andExpect(jsonPath("$.totalCount").doesNotExist());
	}

	@Test
	void 같은삭제시각의카드가있을때_휴지통목록을조회하면_카드ID내림차순으로반환한다() throws Exception {
		Instant deletedAt = Instant.now().minus(1, ChronoUnit.DAYS);
		Long first = insertCard(userId, "첫 상품", null, 100L, "https://image.example/a.jpg", null, true, deletedAt);
		Long second = insertCard(userId, "둘째 상품", null, 200L, "https://image.example/b.jpg", null, true, deletedAt);

		mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].cardId").value(second))
				.andExpect(jsonPath("$.items[1].cardId").value(first));
	}

	@Test
	void 이미지파일과외부이미지가있을때_휴지통목록을조회하면_서명URL을우선하고외부이미지로대체한다() throws Exception {
		Long imageFileId = insertFile("thumbnails/trash/list.webp");
		Long signedCard = insertCard(userId, "서명 상품", "상점", 100L, "https://image.example/original.jpg", imageFileId,
				true, Instant.now().minus(1, ChronoUnit.HOURS));
		Long externalCard = insertCard(userId, "외부 상품", "상점", 200L, "https://image.example/external.jpg", null, true,
				Instant.now().minus(2, ChronoUnit.HOURS));
		Long iconCard = insertCard(userId, "아이콘 상품", "상점", 300L, null, null, true,
				Instant.now().minus(3, ChronoUnit.HOURS));
		AtomicBoolean transactionActive = new AtomicBoolean(true);
		doAnswer(invocation -> {
			transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
			return invocation.callRealMethod();
		}).when(storage).createSignedUrls(any(), any());

		mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].cardId").value(signedCard))
				.andExpect(jsonPath("$.items[0].imageUrl")
						.value(startsWith("https://storage.local/signed/thumbnails/trash/list.webp")))
				.andExpect(jsonPath("$.items[1].cardId").value(externalCard))
				.andExpect(jsonPath("$.items[1].imageUrl").value("https://image.example/external.jpg"))
				.andExpect(jsonPath("$.items[2].cardId").value(iconCard))
				.andExpect(jsonPath("$.items[2].imageUrl").doesNotExist());
		verify(storage, times(1)).createSignedUrls(any(), any());
		assertThat(transactionActive.get()).isFalse();
	}

	private Long insertUser(String email, String nickname) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.users (email, nickname, status) VALUES (?, ?, 'ACTIVE') RETURNING id", Long.class,
				email, nickname);
	}

	private Long insertFile(String storageKey) {
		return jdbc.queryForObject("""
				INSERT INTO ziper.files (storage_key, original_filename, content_type, file_size)
				VALUES (?, 'trash.webp', 'image/webp', 2048)
				RETURNING id
				""", Long.class, storageKey);
	}

	private Long insertCard(Long ownerId, String title, String shop, Long price, String imageUrl, Long imageFileId,
			boolean deleted, Instant deletedAt) {
		return jdbc.queryForObject("""
				INSERT INTO ziper.product_cards (
				    user_id, product_url, shop, product_title, price, image_url, image_file_id, fallback_icon,
				    is_deleted, deleted_at
				)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				RETURNING id
				""", Long.class, ownerId, "https://shop.example/product/" + title, shop, title, price, imageUrl,
				imageFileId, imageUrl == null && imageFileId == null ? "TOP" : null, deleted,
				deletedAt == null ? null : Timestamp.from(deletedAt));
	}

	private void clearDatabase() {
		jdbc.update("DELETE FROM ziper.image_mirror_jobs");
		jdbc.update("DELETE FROM ziper.product_board_card");
		jdbc.update("DELETE FROM ziper.product_cards");
		jdbc.update("DELETE FROM ziper.product_boards");
		jdbc.update("DELETE FROM ziper.files");
		jdbc.update("DELETE FROM ziper.users");
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}
}
