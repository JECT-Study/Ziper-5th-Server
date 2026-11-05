package com.ject.ziper.global.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

class SupabaseBootstrapIntegrationTest {
	@Test
	void 슈퍼유저가아닌DB소유자_부트스트랩하고V1이력에서최신으로마이그레이션하면_소유자는생성역할을쓸수없고런타임역할은업무DML만허용된다() throws Exception {
		try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6").withDatabaseName("postgres")
				.withUsername("test_root").withPassword("test-root-password").withCopyFileToContainer(
						MountableFile.forHostPath(Path.of("db/supabase/bootstrap.sql")), "/tmp/bootstrap.sql")) {
			postgres.start();
			try (Connection root = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword()); Statement statement = root.createStatement()) {
				statement.execute("CREATE ROLE bootstrap_admin LOGIN CREATEROLE NOSUPERUSER NOCREATEDB "
						+ "NOREPLICATION NOBYPASSRLS PASSWORD 'test-bootstrap-password'");
				statement.execute("ALTER DATABASE postgres OWNER TO bootstrap_admin");
				statement.execute("REVOKE CREATE, TEMPORARY ON DATABASE postgres FROM PUBLIC");
				statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
			}
			var result = postgres.execInContainer("env", "PGPASSWORD=test-bootstrap-password", "psql", "-h",
					"127.0.0.1", "-U", "bootstrap_admin", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-v",
					"runtime_password=test-runtime-password", "-v", "migration_password=test-migration-password", "-f",
					"/tmp/bootstrap.sql");
			assertThat(result.getExitCode()).as(result.getStderr()).isZero();
			try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), "bootstrap_admin",
					"test-bootstrap-password"); Statement statement = admin.createStatement()) {
				try (var rows = statement.executeQuery(
						"SELECT pg_get_userbyid(nspowner) FROM pg_namespace " + "WHERE nspname = 'ziper'")) {
					assertThat(rows.next()).isTrue();
					assertThat(rows.getString(1)).isEqualTo("ziper_migrator");
				}
				for (String role : new String[]{"ziper_migrator", "ziper_app"}) {
					try (var rows = statement.executeQuery("SELECT pg_has_role(current_user, '" + role
							+ "', 'SET'), pg_has_role(current_user, '" + role + "', 'USAGE')")) {
						assertThat(rows.next()).isTrue();
						assertThat(rows.getBoolean(1)).isFalse();
						assertThat(rows.getBoolean(2)).isFalse();
					}
				}
				assertDenied(statement, "SET ROLE ziper_migrator");
				try (var rows = statement
						.executeQuery("SELECT rolsuper OR rolcreatedb OR rolreplication OR rolbypassrls "
								+ "FROM pg_roles WHERE rolname = current_user")) {
					assertThat(rows.next()).isTrue();
					assertThat(rows.getBoolean(1)).isFalse();
				}
			}
			try (Connection migration = DriverManager.getConnection(postgres.getJdbcUrl(), "ziper_migrator",
					"test-migration-password"); Statement statement = migration.createStatement()) {
				assertDenied(statement, "CREATE SCHEMA forbidden");
			}
			Flyway versionOne = flyway(postgres, "1");
			assertThat(versionOne.migrate().migrationsExecuted).isOne();
			try (Connection runtime = DriverManager.getConnection(postgres.getJdbcUrl(), "ziper_app",
					"test-runtime-password"); Statement statement = runtime.createStatement()) {
				statement.execute("SELECT * FROM ziper.flyway_schema_history WHERE false");
			}
			Flyway latest = flyway(postgres, "latest");
			assertThat(latest.migrate().migrationsExecuted).isOne();
			assertThat(latest.migrate().migrationsExecuted).isZero();
			try (Connection migration = DriverManager.getConnection(postgres.getJdbcUrl(), "ziper_migrator",
					"test-migration-password"); Statement statement = migration.createStatement()) {
				statement.execute("CREATE TABLE ziper.business_fixture(id bigserial PRIMARY KEY, value text)");
			}
			try (Connection runtime = DriverManager.getConnection(postgres.getJdbcUrl(), "ziper_app",
					"test-runtime-password"); Statement statement = runtime.createStatement()) {
				assertThat(statement.executeUpdate("INSERT INTO ziper.business_fixture(value) VALUES ('first')"))
						.isOne();
				assertThat(statement.executeUpdate("UPDATE ziper.business_fixture SET value = 'second'")).isOne();
				try (var rows = statement.executeQuery("SELECT value FROM ziper.business_fixture")) {
					assertThat(rows.next()).isTrue();
					assertThat(rows.getString(1)).isEqualTo("second");
				}
				assertThat(statement.executeUpdate("DELETE FROM ziper.business_fixture")).isOne();
				assertDenied(statement, "ALTER TABLE ziper.business_fixture ADD COLUMN forbidden text");
				assertDenied(statement, "DROP TABLE ziper.business_fixture");
				assertDenied(statement, "SELECT * FROM ziper.flyway_schema_history WHERE false");
				assertDenied(statement,
						"INSERT INTO ziper.flyway_schema_history(installed_rank) SELECT 999 WHERE false");
				assertDenied(statement, "UPDATE ziper.flyway_schema_history SET success = false WHERE false");
				assertDenied(statement, "DELETE FROM ziper.flyway_schema_history WHERE false");
				assertDenied(statement, "CREATE TABLE ziper.forbidden(id integer)");
				assertDenied(statement, "CREATE SCHEMA forbidden");
			}
		}
	}

	private Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
		return Flyway.configure().dataSource(postgres.getJdbcUrl(), "ziper_migrator", "test-migration-password")
				.schemas("ziper").defaultSchema("ziper").createSchemas(false).target(target).load();
	}

	private void assertDenied(Statement statement, String sql) {
		assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(SQLException.class)
				.satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42501"));
	}
}
