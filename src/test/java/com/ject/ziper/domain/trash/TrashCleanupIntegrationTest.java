package com.ject.ziper.domain.trash;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.ject.ziper.domain.trash.batch.TrashCleanupCoordinator;
import com.ject.ziper.domain.trash.batch.TrashCleanupCoordinator.CleanupResult;
import com.ject.ziper.global.persistence.PostgresTestSupport;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.trash.cleanup.chunk-size=2")
class TrashCleanupIntegrationTest extends PostgresTestSupport {
	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private TrashCleanupCoordinator coordinator;

	private Long userId;

	@BeforeEach
	void setUp() {
		clearDatabase();
		userId = jdbc.queryForObject("""
				INSERT INTO ziper.users (email, nickname, status)
				VALUES ('trash-cleanup@example.com', '만료정리', 'ACTIVE') RETURNING id
				""", Long.class);
	}

	@AfterEach
	void tearDown() {
		clearDatabase();
	}

	@Test
	void 만료카드와보존카드가있을때_정리하면_만료카드와연관행만삭제한다() {
		Long expired = insertCard("expired", true, 31);
		Long recent = insertCard("recent", true, 29);
		Long active = insertCard("active", false, 0);
		Long boundary = insertCard("boundary", true, 30);
		Long boardId = jdbc.queryForObject(
				"INSERT INTO ziper.product_boards (user_id, name) VALUES (?, '정리 보드') RETURNING id", Long.class,
				userId);
		Long tagId = jdbc.queryForObject("SELECT id FROM ziper.tags WHERE title = '봄'", Long.class);
		jdbc.update("INSERT INTO ziper.product_board_card (board_id, card_id) VALUES (?, ?)", boardId, expired);
		jdbc.update("INSERT INTO ziper.product_card_tags (card_id, tag_id) VALUES (?, ?)", expired, tagId);
		jdbc.update("INSERT INTO ziper.user_card_events (user_id, card_id) VALUES (?, ?)", userId, expired);
		jdbc.update("""
				INSERT INTO ziper.image_mirror_jobs (card_id, user_id, source_url, source_url_hash, host)
				VALUES (?, ?, 'https://image.example/item.jpg', ?, 'image.example')
				""", expired, userId, "a".repeat(64));

		CleanupResult result = coordinator.runOnce();

		assertThat(result.skipped()).isFalse();
		assertThat(result.deleted()).isEqualTo(2);
		assertThat(result.chunks()).isOne();
		assertThat(count("product_cards", "id", expired)).isZero();
		assertThat(count("product_cards", "id", boundary)).isZero();
		assertThat(count("product_cards", "id", recent)).isOne();
		assertThat(count("product_cards", "id", active)).isOne();
		for (String table : new String[]{"product_board_card", "product_card_tags", "user_card_events",
				"image_mirror_jobs"}) {
			assertThat(count(table, "card_id", expired)).isZero();
		}
	}

	@Test
	void 만료카드가청크보다많을때_정리하면_모든청크를처리하고공유파일을보존한다() {
		Long fileId = jdbc.queryForObject("""
				INSERT INTO ziper.files (storage_key, original_filename, content_type, file_size, owner_user_id)
				VALUES ('trash-cleanup/shared.webp', 'shared.webp', 'image/webp', 10, ?)
				RETURNING id
				""", Long.class, userId);
		Long first = insertCard("first", true, 31);
		Long retained = insertCard("retained", false, 0);
		jdbc.update("UPDATE ziper.product_cards SET image_file_id = ?, image_status = 'READY' WHERE id IN (?, ?)",
				fileId, first, retained);
		insertCard("second", true, 31);
		insertCard("third", true, 31);
		insertCard("fourth", true, 31);
		insertCard("fifth", true, 31);

		CleanupResult result = coordinator.runOnce();

		assertThat(result.deleted()).isEqualTo(5);
		assertThat(result.chunks()).isEqualTo(3);
		assertThat(count("product_cards", "id", retained)).isOne();
		assertThat(count("files", "id", fileId)).isOne();
	}

	@Test
	void 다른인스턴스가락을보유할때_정리하면_해당주기를건너뛴다() throws Exception {
		Long expired = insertCard("locked", true, 31);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			Future<?> holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
				jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
						"trash-cleanup:expired-products");
				locked.countDown();
				try {
					if (!release.await(10, TimeUnit.SECONDS)) {
						throw new IllegalStateException("Timed out waiting to release cleanup lock");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
			}));
			assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
			CleanupResult result = coordinator.runOnce();
			assertThat(result.skipped()).isTrue();
			assertThat(result.deleted()).isZero();
			assertThat(count("product_cards", "id", expired)).isOne();
			release.countDown();
			holder.get(10, TimeUnit.SECONDS);
		} finally {
			release.countDown();
		}
		assertThat(coordinator.runOnce().deleted()).isOne();
	}

	private Long insertCard(String suffix, boolean deleted, int daysAgo) {
		return jdbc.queryForObject("""
				INSERT INTO ziper.product_cards (
					user_id, product_url, product_title, price, image_url, is_deleted, deleted_at
				)
				VALUES (?, ?, '테스트 상품', 1000, 'https://image.example/item.jpg', ?,
					CASE WHEN ? THEN CURRENT_TIMESTAMP - (? * INTERVAL '1 day') ELSE NULL END)
				RETURNING id
				""", Long.class, userId, "https://shop.example/cleanup-" + suffix, deleted, deleted, daysAgo);
	}

	private int count(String table, String column, Long id) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM ziper." + table + " WHERE " + column + " = ?", Integer.class,
				id);
	}

	private void clearDatabase() {
		jdbc.update("DELETE FROM ziper.image_mirror_jobs");
		jdbc.update("DELETE FROM ziper.user_card_events");
		jdbc.update("DELETE FROM ziper.product_card_tags");
		jdbc.update("DELETE FROM ziper.product_board_card");
		jdbc.update("DELETE FROM ziper.product_cards");
		jdbc.update("DELETE FROM ziper.product_boards");
		jdbc.update("DELETE FROM ziper.files");
		jdbc.update("DELETE FROM ziper.users");
	}
}
