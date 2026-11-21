package com.ject.ziper.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class ProductCardV12MigrationIntegrationTest {
	@Test
	void V11까지적용된기존데이터가있을때_V12마이그레이션을실행하면_백필과제약조건이정확히적용된다() throws Exception {
		try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
				.withDatabaseName("ziper_v12_test").withUsername("ziper_migrator")
				.withPassword("test-migration-password")) {
			postgres.start();
			try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword()); Statement stmt = conn.createStatement()) {
				stmt.execute("CREATE ROLE ziper_app LOGIN PASSWORD 'test-runtime-password'");
				stmt.execute("CREATE SCHEMA ziper AUTHORIZATION ziper_migrator");
			}

			Flyway v11 = Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).schemas("ziper")
					.defaultSchema("ziper").createSchemas(false).target("11").load();
			v11.migrate();

			long userId;
			long nullLegacyCardId;
			long fileBackedCardId;
			long mirroredCardId;
			long fileBackedFileId;
			try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword())) {
				userId = insertUser(conn);
				long fileId = insertFile(conn, "thumbnails/v12.webp");
				fileBackedFileId = fileId;
				long mirroredFileId = insertFile(conn, "thumbnails/v12-mirrored.webp");
				nullLegacyCardId = insertV11Card(conn, userId, "https://shop.example/items/legacy", "레거시 상품", null,
						null);
				fileBackedCardId = insertV11Card(conn, userId, "https://shop.example/items/file", "파일 상품", 25000L,
						fileId);
				mirroredCardId = insertV11Card(conn, userId, "https://shop.example/items/mirrored", "미러링 상품", 1000L,
						mirroredFileId);
				try (PreparedStatement ps = conn.prepareStatement(
						"UPDATE ziper.product_cards SET image_url = 'https://cdn.shop.example/m.jpg' WHERE id = ?")) {
					ps.setLong(1, mirroredCardId);
					ps.executeUpdate();
				}
			}

			Flyway v12 = Flyway.configure()
					.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).schemas("ziper")
					.defaultSchema("ziper").createSchemas(false).target("12").load();
			assertThat(v12.migrate().migrationsExecuted).isEqualTo(1);

			try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword()); Statement stmt = conn.createStatement()) {
				try (PreparedStatement ps = conn.prepareStatement(
						"SELECT price, fallback_icon, metadata_source, image_status FROM ziper.product_cards WHERE id = ?")) {
					ps.setLong(1, nullLegacyCardId);
					try (ResultSet rs = ps.executeQuery()) {
						assertThat(rs.next()).isTrue();
						assertThat(rs.getLong("price")).isZero();
						assertThat(rs.getString("fallback_icon")).isEqualTo("TOP");
						assertThat(rs.getString("metadata_source")).isEqualTo("MANUAL");
						assertThat(rs.getString("image_status")).isEqualTo("NONE");
					}
				}

				try (PreparedStatement ps = conn
						.prepareStatement("SELECT price, image_status FROM ziper.product_cards WHERE id = ?")) {
					ps.setLong(1, fileBackedCardId);
					try (ResultSet rs = ps.executeQuery()) {
						assertThat(rs.next()).isTrue();
						assertThat(rs.getLong("price")).isEqualTo(25000L);
						assertThat(rs.getString("image_status")).isEqualTo("NONE");
					}
				}

				try (PreparedStatement ps = conn
						.prepareStatement("SELECT image_status FROM ziper.product_cards WHERE id = ?")) {
					ps.setLong(1, mirroredCardId);
					try (ResultSet rs = ps.executeQuery()) {
						assertThat(rs.next()).isTrue();
						assertThat(rs.getString("image_status")).isEqualTo("READY");
					}
				}

				assertThatThrownBy(() -> stmt.executeUpdate("DELETE FROM ziper.files WHERE id = " + fileBackedFileId))
						.isInstanceOf(SQLException.class).hasMessageContaining("fk_product_cards_image_file_id");

				assertThatThrownBy(() -> stmt.executeUpdate(
						"INSERT INTO ziper.product_cards(user_id, product_url, product_title, price, fallback_icon) "
								+ "VALUES (" + userId + ", 'https://shop.example/null-price', '가격없음', NULL, 'TOP')"))
						.isInstanceOf(SQLException.class);

				assertThatThrownBy(() -> stmt
						.executeUpdate("INSERT INTO ziper.product_cards(user_id, product_url, product_title, price) "
								+ "VALUES (" + userId + ", 'https://shop.example/no-image', '이미지없음', 10000)"))
						.isInstanceOf(SQLException.class);

				assertThatThrownBy(() -> stmt.executeUpdate(
						"INSERT INTO ziper.product_cards(user_id, product_url, product_title, price, fallback_icon, metadata_source) "
								+ "VALUES (" + userId
								+ ", 'https://shop.example/bad-src', '출처오류', 10000, 'TOP', 'INVALID')"))
						.isInstanceOf(SQLException.class);

				assertThatThrownBy(() -> stmt.executeUpdate(
						"INSERT INTO ziper.product_cards(user_id, product_url, product_title, price, fallback_icon, image_status) "
								+ "VALUES (" + userId
								+ ", 'https://shop.example/bad-img-status', '상태오류', 10000, 'TOP', 'INVALID')"))
						.isInstanceOf(SQLException.class);
			}
		}
	}

	private long insertUser(Connection conn) throws Exception {
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO ziper.users(email, nickname, status) VALUES ('v12@ziper.test', '유저V12', 'ACTIVE') RETURNING id")) {
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	private long insertFile(Connection conn, String storageKey) throws Exception {
		try (PreparedStatement ps = conn
				.prepareStatement("INSERT INTO ziper.files(storage_key, original_filename, content_type, file_size) "
						+ "VALUES (?, 'v12.webp', 'image/webp', 512) RETURNING id")) {
			ps.setString(1, storageKey);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	private long insertV11Card(Connection conn, long userId, String url, String title, Long price, Long fileId)
			throws Exception {
		try (PreparedStatement ps = conn.prepareStatement(
				"INSERT INTO ziper.product_cards(user_id, product_url, product_title, price, image_file_id) "
						+ "VALUES (?, ?, ?, ?, ?) RETURNING id")) {
			ps.setLong(1, userId);
			ps.setString(2, url);
			ps.setString(3, title);
			if (price == null) {
				ps.setObject(4, null);
			} else {
				ps.setLong(4, price);
			}
			if (fileId == null) {
				ps.setObject(5, null);
			} else {
				ps.setLong(5, fileId);
			}
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}
}
