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
	void startupCanRepeatAgainstTheSameDatabase() {
		assertThat(flyway.info().pending()).isEmpty();
		assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("ziper_app");
	}

	@Test
	void migrationsAreRepeatableAndRuntimeHasOnlyDataPrivileges() throws SQLException {
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
