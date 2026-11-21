package com.ject.ziper.domain.tag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

class TagMigrationIntegrationTest {
	@Test
	void V6까지적용된커스텀태그데이터_V8을적용하면_소유자이관과사용자별분리및미연결삭제가수행된다() throws Exception {
		try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6").withDatabaseName("ziper")
				.withUsername("ziper_admin").withPassword("test-admin-password")
				.withEnv("DB_PASSWORD", "test-runtime-password")
				.withEnv("MIGRATION_PASSWORD", "test-migration-password")
				.withCopyFileToContainer(MountableFile.forHostPath(Path.of("db/init/001-roles.sh"), 0755),
						"/docker-entrypoint-initdb.d/001-roles.sh")) {
			postgres.start();
			assertThat(flyway(postgres, "6").migrate().migrationsExecuted).isEqualTo(6);
			try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), "ziper_migrator",
					"test-migration-password"); Statement statement = connection.createStatement()) {
				statement.execute("INSERT INTO ziper.users(email, nickname, status) VALUES "
						+ "('a@ziper.test', 'A', 'ACTIVE'), ('b@ziper.test', 'B', 'ACTIVE')");
				long userA = queryLong(statement, "SELECT id FROM ziper.users WHERE email = 'a@ziper.test'");
				long userB = queryLong(statement, "SELECT id FROM ziper.users WHERE email = 'b@ziper.test'");
				statement.execute("INSERT INTO ziper.tags(title, is_default) VALUES ('공유', FALSE), ('단독', FALSE), "
						+ "('미연결', FALSE)");
				long shared = queryLong(statement, "SELECT id FROM ziper.tags WHERE title = '공유'");
				long solo = queryLong(statement, "SELECT id FROM ziper.tags WHERE title = '단독'");
				statement.execute("INSERT INTO ziper.product_cards(user_id, product_url, product_title) VALUES ("
						+ userA + ", 'https://s.example/a1', 'a1'), (" + userA + ", 'https://s.example/a2', 'a2'), ("
						+ userB + ", 'https://s.example/b1', 'b1')");
				long cardA1 = queryLong(statement,
						"SELECT id FROM ziper.product_cards WHERE product_url = 'https://s.example/a1'");
				long cardA2 = queryLong(statement,
						"SELECT id FROM ziper.product_cards WHERE product_url = 'https://s.example/a2'");
				long cardB1 = queryLong(statement,
						"SELECT id FROM ziper.product_cards WHERE product_url = 'https://s.example/b1'");
				statement.execute("INSERT INTO ziper.product_card_tags(card_id, tag_id) VALUES (" + cardA1 + ", "
						+ shared + "), (" + cardA2 + ", " + shared + "), (" + cardB1 + ", " + shared + "), (" + cardA1
						+ ", " + solo + ")");

				assertThat(flyway(postgres, "latest").migrate().migrationsExecuted).isGreaterThanOrEqualTo(1);

				assertThat(strings(statement, "SELECT title FROM ziper.tags WHERE user_id IS NULL ORDER BY id"))
						.containsExactly("봄", "여름", "가을", "겨울");
				assertThat(strings(statement, "SELECT title FROM ziper.tags WHERE title = '미연결'")).isEmpty();
				assertThat(queryLong(statement, "SELECT user_id FROM ziper.tags WHERE id = " + solo)).isEqualTo(userA);
				assertThat(queryLong(statement,
						"SELECT COUNT(*) FROM ziper.tags WHERE title = '공유' AND is_default = FALSE")).isEqualTo(2);
				assertThat(queryLong(statement, "SELECT user_id FROM ziper.tags WHERE id = " + shared))
						.isEqualTo(userA);
				long userBTag = queryLong(statement,
						"SELECT id FROM ziper.tags WHERE title = '공유' AND user_id = " + userB);
				assertThat(userBTag).isNotEqualTo(shared);
				assertThat(queryLong(statement, "SELECT tag_id FROM ziper.product_card_tags WHERE card_id = " + cardB1))
						.isEqualTo(userBTag);
				assertThat(
						queryLong(statement, "SELECT COUNT(*) FROM ziper.product_card_tags WHERE tag_id = " + shared))
						.isEqualTo(2);
			}
		}
	}

	private long queryLong(Statement statement, String sql) throws Exception {
		try (ResultSet rows = statement.executeQuery(sql)) {
			assertThat(rows.next()).isTrue();
			return rows.getLong(1);
		}
	}

	private List<String> strings(Statement statement, String sql) throws Exception {
		List<String> values = new ArrayList<>();
		try (ResultSet rows = statement.executeQuery(sql)) {
			while (rows.next()) {
				values.add(rows.getString(1));
			}
		}
		return values;
	}

	private Flyway flyway(PostgreSQLContainer<?> postgres, String target) {
		return Flyway.configure().dataSource(postgres.getJdbcUrl(), "ziper_migrator", "test-migration-password")
				.schemas("ziper").defaultSchema("ziper").createSchemas(true).target(target).load();
	}
}
