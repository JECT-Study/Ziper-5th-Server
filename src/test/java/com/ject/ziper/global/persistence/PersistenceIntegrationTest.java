package com.ject.ziper.global.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PersistenceIntegrationTest extends PostgresTestSupport {
	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Flyway flyway;

	@Test
	void 이미사용중인DB_애플리케이션을다시기동하면_대기마이그레이션없이런타임역할로연결한다() {
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("ziper_app");
	}

	@Test
	void 마이그레이션이끝난DB_다시마이그레이션하고런타임역할로쿼리하면_추가실행없이업무테이블DML만허용하고DDL과이력테이블접근은거부한다() throws SQLException {
		assertThat(flyway.migrate().migrationsExecuted).isZero();
		assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("ziper_app");
		assertThat(jdbc.queryForObject("SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'ziper'",
				String.class)).isEqualTo("ziper_migrator");
		assertThat(jdbc.queryForObject("SELECT rolbypassrls OR rolsuper OR rolcreatedb OR rolcreaterole "
				+ "FROM pg_roles WHERE rolname = current_user", Boolean.class)).isFalse();
		assertThat(jdbc.queryForObject("SELECT has_database_privilege(current_user, current_database(), 'CREATE')",
				Boolean.class)).isFalse();
		assertThat(jdbc.queryForObject("SELECT has_schema_privilege(current_user, 'public', 'CREATE')", Boolean.class))
				.isFalse();
		for (String sql : new String[]{"SELECT * FROM ziper.flyway_schema_history WHERE false",
				"INSERT INTO ziper.flyway_schema_history(installed_rank) SELECT 999 WHERE false",
				"UPDATE ziper.flyway_schema_history SET success = false WHERE false",
				"DELETE FROM ziper.flyway_schema_history WHERE false"}) {
			assertThatThrownBy(() -> jdbc.execute(sql)).hasRootCauseInstanceOf(SQLException.class)
					.satisfies(error -> assertThat(((SQLException) error.getCause()).getSQLState()).isEqualTo("42501"));
		}
		try (Connection migration = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "ziper_migrator",
				MIGRATION_PASSWORD); Statement statement = migration.createStatement()) {
			statement.execute("CREATE TABLE ziper.persistence_fixture (id bigserial PRIMARY KEY, value text NOT NULL)");
			try {
				Long id = jdbc.queryForObject(
						"INSERT INTO ziper.persistence_fixture(value) VALUES ('first') RETURNING id", Long.class);
				assertThat(jdbc.update("UPDATE ziper.persistence_fixture SET value = 'second' WHERE id = ?", id))
						.isOne();
				assertThat(jdbc.queryForObject("SELECT value FROM ziper.persistence_fixture WHERE id = ?", String.class,
						id)).isEqualTo("second");
				assertThat(jdbc.update("DELETE FROM ziper.persistence_fixture WHERE id = ?", id)).isOne();
				assertThatThrownBy(() -> jdbc.execute("CREATE TABLE ziper.forbidden (id integer)"))
						.hasRootCauseInstanceOf(SQLException.class);
				assertThatThrownBy(
						() -> jdbc.execute("ALTER TABLE ziper.persistence_fixture ADD COLUMN forbidden text"))
						.hasRootCauseInstanceOf(SQLException.class);
				assertThatThrownBy(() -> jdbc.execute("DROP TABLE ziper.persistence_fixture"))
						.hasRootCauseInstanceOf(SQLException.class);
			} finally {
				statement.execute("DROP TABLE ziper.persistence_fixture");
			}
		}
	}

	@Test
	void V6상품과태그테이블이적용된DB_기본태그와카드제약조건을검증하면_시드와무결성제약이정상동작한다() {
		assertThat(jdbc.queryForList("SELECT title FROM ziper.tags WHERE is_default = TRUE ORDER BY id", String.class))
				.containsExactly("봄", "여름", "가을", "겨울");

		Long userId = jdbc.queryForObject(
				"INSERT INTO ziper.users(email, nickname, status) VALUES ('v6@ziper.test', '집퍼V6', 'ACTIVE') RETURNING id",
				Long.class);
		Long boardId = jdbc.queryForObject(
				"INSERT INTO ziper.product_boards(user_id, name, modifiable) VALUES (?, '기본 보드', FALSE) RETURNING id",
				Long.class, userId);

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO ziper.product_boards(user_id, name, modifiable) VALUES (?, '기본 보드', TRUE)", userId))
				.hasRootCauseInstanceOf(SQLException.class);

		Long fileId = jdbc
				.queryForObject("INSERT INTO ziper.files(storage_key, original_filename, content_type, file_size) "
						+ "VALUES ('products/1.webp', '1.webp', 'image/webp', 1024) RETURNING id", Long.class);
		Long cardId = jdbc.queryForObject(
				"INSERT INTO ziper.product_cards(user_id, product_url, product_title, price, image_file_id, fallback_icon) "
						+ "VALUES (?, 'https://store.example/items/1', '봄 가디건', 39000, ?, 'TOP') RETURNING id",
				Long.class, userId, fileId);

		assertThatThrownBy(() -> jdbc
				.update("INSERT INTO ziper.product_cards(user_id, product_url, product_title, price, fallback_icon) "
						+ "VALUES (?, 'https://store.example/items/1', '중복 상품', 39000, 'TOP')", userId))
				.hasRootCauseInstanceOf(SQLException.class);

		Long tagId = jdbc.queryForObject("SELECT id FROM ziper.tags WHERE title = '봄'", Long.class);
		assertThat(
				jdbc.update("INSERT INTO ziper.product_board_card(board_id, card_id) VALUES (?, ?)", boardId, cardId))
				.isOne();
		assertThat(jdbc.update("INSERT INTO ziper.product_card_tags(card_id, tag_id) VALUES (?, ?)", cardId, tagId))
				.isOne();
		assertThat(jdbc.update(
				"INSERT INTO ziper.user_card_events(user_id, card_id, event_type) VALUES (?, ?, 'VIEW_DETAIL')", userId,
				cardId)).isOne();

		jdbc.update("DELETE FROM ziper.users WHERE id = ?", userId);
		jdbc.update("DELETE FROM ziper.files WHERE id = ?", fileId);
	}
}
