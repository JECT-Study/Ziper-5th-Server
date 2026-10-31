package com.ject.ziper.global.persistence;

import java.nio.file.Path;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

public abstract class PostgresTestSupport {
	protected static final String RUNTIME_PASSWORD = "test-runtime-password";
	protected static final String MIGRATION_PASSWORD = "test-migration-password";
	protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6")
			.withDatabaseName("ziper").withUsername("ziper_admin").withPassword("test-admin-password")
			.withEnv("DB_PASSWORD", RUNTIME_PASSWORD).withEnv("MIGRATION_PASSWORD", MIGRATION_PASSWORD)
			.withCopyFileToContainer(MountableFile.forHostPath(Path.of("db/init/001-roles.sh"), 0755),
					"/docker-entrypoint-initdb.d/001-roles.sh");

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void postgresProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", () -> "ziper_app");
		registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
		registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
		registry.add("spring.flyway.user", () -> "ziper_migrator");
		registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
	}
}
