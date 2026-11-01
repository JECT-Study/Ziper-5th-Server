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
}
