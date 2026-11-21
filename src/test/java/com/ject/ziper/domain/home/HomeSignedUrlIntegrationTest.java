package com.ject.ziper.domain.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.ject.ziper.domain.home.dto.HomeRecentProductResponse;
import com.ject.ziper.domain.home.dto.HomeSummaryResponse;
import com.ject.ziper.domain.home.service.HomeQueryFacade;
import com.ject.ziper.domain.product.entity.FallbackIcon;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.storage.ObjectStorageClient;

@SpringBootTest
@ActiveProfiles("test")
class HomeSignedUrlIntegrationTest extends PostgresTestSupport {
	@Autowired
	private HomeQueryFacade facade;

	@Autowired
	private JdbcTemplate jdbc;

	@MockitoSpyBean
	private ObjectStorageClient objectStorageClient;

	private Long userId;
	private Long boardOneId;
	private Long boardTwoId;
	private Long fileOneId;
	private Long fileTwoId;
	private Long missingFileId;
	private Long cardMissingId;

	@BeforeEach
	void setUp() {
		clearDatabase();
		Instant base = Instant.parse("2026-11-10T10:00:00Z");
		userId = jdbc.queryForObject(
				"INSERT INTO ziper.users (email, nickname, status) VALUES ('sign@example.com', '서명', 'ACTIVE') RETURNING id",
				Long.class);
		boardOneId = insertBoard("보드1", base);
		boardTwoId = insertBoard("보드2", base.plusSeconds(5));
		fileOneId = insertFile("thumbs/one.webp");
		fileTwoId = insertFile("thumbs/two.webp");
		missingFileId = insertFile("thumbs/missing.webp");

		Long cardA = insertCard("a", "https://img.example/a.jpg", fileOneId, null, base.plusSeconds(1));
		Long cardB = insertCard("b", null, fileTwoId, "TOP", base.plusSeconds(2));
		cardMissingId = insertCard("c", "https://img.example/c.jpg", missingFileId, "TOP", base.plusSeconds(3));
		Long cardD = insertCard("d", null, fileOneId, "ACCESSORY", base.plusSeconds(4));
		link(boardOneId, cardA, base);
		link(boardOneId, cardMissingId, base);
		link(boardTwoId, cardB, base);
		link(boardTwoId, cardD, base);
	}

	@AfterEach
	void tearDown() {
		clearDatabase();
	}

	@Test
	void 여러카드와보드가같은파일을공유할때_홈요약을조회하면_일괄서명호출은한번만발생한다() {
		List<Collection<String>> requestedKeys = new ArrayList<>();
		doAnswer(invocation -> {
			requestedKeys.add(List.copyOf(invocation.<Collection<String>>getArgument(0)));
			return invocation.callRealMethod();
		}).when(objectStorageClient).createSignedUrls(any(), any());

		HomeSummaryResponse response = facade.getHomeSummary(userId, 10);

		verify(objectStorageClient, times(1)).createSignedUrls(any(), any());
		assertThat(requestedKeys).hasSize(1);
		assertThat(requestedKeys.get(0)).containsExactlyInAnyOrder("thumbs/one.webp", "thumbs/two.webp",
				"thumbs/missing.webp");
		assertThat(response.boards()).hasSize(2);
		assertThat(response.recentProducts()).hasSize(4);
		assertThat(response.recentProducts())
				.allSatisfy(product -> assertThat(product.imageFileUrl()).startsWith("https://storage.local/signed/"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void 일괄서명결과에서일부파일이빠질때_홈요약을조회하면_해당경로만원본이미지로폴백한다() {
		doAnswer(invocation -> {
			Map<String, String> signed = new HashMap<>((Map<String, String>) invocation.callRealMethod());
			signed.remove("thumbs/missing.webp");
			return signed;
		}).when(objectStorageClient).createSignedUrls(any(), any());

		HomeSummaryResponse response = facade.getHomeSummary(userId, 10);

		HomeRecentProductResponse missing = response.recentProducts().stream()
				.filter(product -> product.cardId().equals(cardMissingId)).findFirst().orElseThrow();
		assertThat(missing.imageFileUrl()).isNull();
		assertThat(missing.imageUrl()).isEqualTo("https://img.example/c.jpg");
		assertThat(missing.fallbackIcon()).isEqualTo(FallbackIcon.TOP);
		assertThat(response.recentProducts().stream().filter(product -> !product.cardId().equals(cardMissingId)))
				.allSatisfy(product -> assertThat(product.imageFileUrl()).startsWith("https://storage.local/signed/"));

		var boardOnePreviews = response.boards().stream().filter(board -> board.boardId().equals(boardOneId))
				.findFirst().orElseThrow().previewImages();
		var missingPreview = boardOnePreviews.stream().filter(preview -> preview.cardId().equals(cardMissingId))
				.findFirst().orElseThrow();
		assertThat(missingPreview.imageUrl()).isEqualTo("https://img.example/c.jpg");
		assertThat(missingPreview.fallbackIcon()).isEqualTo(FallbackIcon.TOP);
		var signedPreview = boardOnePreviews.stream().filter(preview -> !preview.cardId().equals(cardMissingId))
				.findFirst().orElseThrow();
		assertThat(signedPreview.imageUrl()).startsWith("https://storage.local/signed/thumbs/one.webp");
	}

	@Test
	void 서명을호출하는시점에는_홈요약을조회하면_DB트랜잭션이열려있지않다() {
		AtomicBoolean transactionActive = new AtomicBoolean(true);
		doAnswer(invocation -> {
			transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
			return invocation.callRealMethod();
		}).when(objectStorageClient).createSignedUrls(any(), any());

		facade.getHomeSummary(userId, 10);

		verify(objectStorageClient, times(1)).createSignedUrls(any(), any());
		assertThat(transactionActive).isFalse();
	}

	@Test
	void 서명호출전체가실패할때_홈요약을조회하면_폴백없이예외를전파한다() {
		IllegalStateException storageDown = new IllegalStateException("down");
		doAnswer(invocation -> {
			throw storageDown;
		}).when(objectStorageClient).createSignedUrls(any(), any());

		assertThatThrownBy(() -> facade.getHomeSummary(userId, 10)).isSameAs(storageDown);
	}

	@Test
	void 파일이없는카드만있을때_홈요약을조회하면_서명호출을하지않는다() {
		jdbc.update("UPDATE ziper.product_cards SET image_file_id = NULL WHERE user_id = ?", userId);

		HomeSummaryResponse response = facade.getHomeSummary(userId, 10);

		verify(objectStorageClient, never()).createSignedUrls(any(), any());
		assertThat(response.recentProducts()).allSatisfy(product -> assertThat(product.imageFileUrl()).isNull());
	}

	private Long insertBoard(String name, Instant createdAt) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.product_boards (user_id, name, modifiable, is_deleted, created_at) "
						+ "VALUES (?, ?, true, false, ?) RETURNING id",
				Long.class, userId, name, Timestamp.from(createdAt));
	}

	private Long insertFile(String storageKey) {
		return jdbc.queryForObject("INSERT INTO ziper.files (storage_key, original_filename, content_type, file_size) "
				+ "VALUES (?, 'f.webp', 'image/webp', 10) RETURNING id", Long.class, storageKey);
	}

	private Long insertCard(String slug, String imageUrl, Long imageFileId, String fallbackIcon, Instant createdAt) {
		Instant future = Instant.now().plus(14, ChronoUnit.DAYS);
		return jdbc.queryForObject("""
				INSERT INTO ziper.product_cards (
				    user_id, product_url, shop, product_title, price, image_url, image_file_id,
				    fallback_icon, metadata_source, image_status, status, link_status,
				    last_interacted_at, next_rediscovery_at, is_deleted, created_at, updated_at
				) VALUES (?, ?, '무신사', ?, 1000, ?, ?, ?, 'MANUAL', 'READY', 'PLANNED', 'UNKNOWN', ?, ?, false, ?, ?)
				RETURNING id
				""", Long.class, userId, "https://www.musinsa.com/products/" + slug, "상품" + slug, imageUrl, imageFileId,
				fallbackIcon, Timestamp.from(createdAt), Timestamp.from(future), Timestamp.from(createdAt),
				Timestamp.from(createdAt));
	}

	private void link(Long boardId, Long cardId, Instant createdAt) {
		jdbc.update("INSERT INTO ziper.product_board_card (board_id, card_id, created_at) VALUES (?, ?, ?)", boardId,
				cardId, Timestamp.from(createdAt));
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
}
