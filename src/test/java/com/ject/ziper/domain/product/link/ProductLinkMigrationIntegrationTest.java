package com.ject.ziper.domain.product.link;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class ProductLinkMigrationIntegrationTest {
	@Test
	void V6까지적용된데이터가있을때_V11마이그레이션을실행하면_링크테이블백필과컬럼제거및부분인덱스가반영된다() throws Exception {
		try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
				.withDatabaseName("ziper_v11_test").withUsername("ziper_migrator")
				.withPassword("test-migration-password")) {
			postgres.start();
			try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword()); Statement stmt = conn.createStatement()) {
				stmt.execute("CREATE ROLE ziper_app LOGIN PASSWORD 'test-runtime-password'");
				stmt.execute("CREATE SCHEMA ziper AUTHORIZATION ziper_migrator");
			}

			Flyway v6 = Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).schemas("ziper")
					.defaultSchema("ziper").createSchemas(false).target("6").load();
			v6.migrate();

			long user1Id;
			long user2Id;
			long validSharedCardId;
			long invalidSharedCardId;
			long validUniqueCardId;
			try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword())) {
				user1Id = insertUser(conn, "u1@ziper.test", "유저일");
				user2Id = insertUser(conn, "u2@ziper.test", "유저이");
				validSharedCardId = insertV6Card(conn, user1Id, "https://shop.example/items/shared", "공유 상품 1", true);
				invalidSharedCardId = insertV6Card(conn, user2Id, "https://shop.example/items/shared", "공유 상품 2",
						false);
				validUniqueCardId = insertV6Card(conn, user1Id, "https://shop.example/items/unique", "단독 상품", true);
			}

			Flyway v11 = Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).schemas("ziper")
					.defaultSchema("ziper").createSchemas(false).target("11").load();
			assertThat(v11.migrate().migrationsExecuted).isEqualTo(1);

			try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword()); Statement stmt = conn.createStatement()) {
				try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
						+ "WHERE table_schema = 'ziper' AND table_name = 'product_cards' "
						+ "AND column_name = 'is_link_valid'")) {
					assertThat(rs.next()).isTrue();
					assertThat(rs.getInt(1)).isZero();
				}

				Map<String, Long> linkIdsByUrl = new HashMap<>();
				try (ResultSet rs = stmt
						.executeQuery("SELECT id, canonical_url, status FROM ziper.product_links ORDER BY id")) {
					while (rs.next()) {
						linkIdsByUrl.put(rs.getString("canonical_url"), rs.getLong("id"));
						assertThat(rs.getString("status")).isEqualTo("UNKNOWN");
					}
				}
				assertThat(linkIdsByUrl).containsOnlyKeys("https://shop.example/items/shared",
						"https://shop.example/items/unique");

				assertCardMigration(conn, validSharedCardId, linkIdsByUrl.get("https://shop.example/items/shared"),
						"UNKNOWN");
				assertCardMigration(conn, invalidSharedCardId, linkIdsByUrl.get("https://shop.example/items/shared"),
						"REMOVED");
				assertCardMigration(conn, validUniqueCardId, linkIdsByUrl.get("https://shop.example/items/unique"),
						"UNKNOWN");

				try (ResultSet rs = stmt.executeQuery("SELECT indexdef FROM pg_indexes "
						+ "WHERE schemaname = 'ziper' AND tablename = 'product_cards' "
						+ "AND indexname = 'idx_product_cards_rediscovery_queue'")) {
					assertThat(rs.next()).isTrue();
					String indexDef = rs.getString(1);
					assertThat(indexDef).contains("REMOVED").contains("SOLD_OUT").contains("link_status");
				}

				stmt.executeUpdate(
						"UPDATE ziper.product_cards SET link_status = 'AVAILABLE' WHERE id = " + validUniqueCardId);
				long soldOutCardId = insertV11Card(conn, user2Id, "https://shop.example/items/soldout", "품절 상품",
						"SOLD_OUT");

				try (ResultSet rs = stmt.executeQuery(
						"SELECT id FROM ziper.product_cards " + "WHERE is_deleted = FALSE AND status = 'PLANNED' "
								+ "AND link_status NOT IN ('REMOVED', 'SOLD_OUT') ORDER BY id")) {
					assertThat(rs.next()).isTrue();
					assertThat(rs.getLong(1)).isEqualTo(validSharedCardId);
					assertThat(rs.next()).isTrue();
					assertThat(rs.getLong(1)).isEqualTo(validUniqueCardId);
					assertThat(rs.next()).isFalse();
				}
				assertThat(soldOutCardId).isPositive();

				try (ResultSet rs = stmt.executeQuery(
						"SELECT COUNT(*) FROM information_schema.tables " + "WHERE table_schema = 'ziper' "
								+ "AND table_name IN ('product_links', 'product_link_reports')")) {
					assertThat(rs.next()).isTrue();
					assertThat(rs.getInt(1)).isEqualTo(2);
				}
			}
		}
	}

	private long insertUser(Connection conn, String email, String nickname) throws Exception {
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO ziper.users(email, nickname, status) VALUES (?, ?, 'ACTIVE') RETURNING id")) {
			ps.setString(1, email);
			ps.setString(2, nickname);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	private long insertV6Card(Connection conn, long userId, String url, String title, boolean linkValid)
			throws Exception {
		try (PreparedStatement ps = conn
				.prepareStatement("INSERT INTO ziper.product_cards(user_id, product_url, product_title, is_link_valid) "
						+ "VALUES (?, ?, ?, ?) RETURNING id")) {
			ps.setLong(1, userId);
			ps.setString(2, url);
			ps.setString(3, title);
			ps.setBoolean(4, linkValid);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	private long insertV11Card(Connection conn, long userId, String url, String title, String linkStatus)
			throws Exception {
		try (PreparedStatement ps = conn
				.prepareStatement("INSERT INTO ziper.product_cards(user_id, product_url, product_title, link_status) "
						+ "VALUES (?, ?, ?, ?) RETURNING id")) {
			ps.setLong(1, userId);
			ps.setString(2, url);
			ps.setString(3, title);
			ps.setString(4, linkStatus);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	private void assertCardMigration(Connection conn, long cardId, long expectedLinkId, String expectedStatus)
			throws Exception {
		try (PreparedStatement ps = conn
				.prepareStatement("SELECT link_id, link_status FROM ziper.product_cards WHERE id = ?")) {
			ps.setLong(1, cardId);
			try (ResultSet rs = ps.executeQuery()) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getLong("link_id")).isEqualTo(expectedLinkId);
				assertThat(rs.getString("link_status")).isEqualTo(expectedStatus);
			}
		}
	}
}
