package com.ject.ziper.global.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 현재 트랜잭션이 끝날 때까지 유지되는 PostgreSQL advisory lock. 인스턴스 간에 같은 키의 쓰기를 직렬화한다. 키
 * 접두사(flow:, email:, nickname:, oauth:)는 기능 간에 공유되므로 잠그는 순서를 바꾸지 않는다.
 */
@Repository
public class AdvisoryLockRepository {
	private final JdbcTemplate jdbc;

	public AdvisoryLockRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void lock(String key) {
		jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", result -> {
		}, key);
	}
}
