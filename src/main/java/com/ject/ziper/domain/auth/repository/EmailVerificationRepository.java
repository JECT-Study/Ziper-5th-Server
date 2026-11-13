package com.ject.ziper.domain.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class EmailVerificationRepository {
	private final JdbcTemplate jdbc;

	public EmailVerificationRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void lock(String key) {
		jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", result -> {
		}, key);
	}

	public int countByEmail(String email, Instant since) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM ziper.auth_codes WHERE email = ? AND requested_at >= ?",
				Integer.class, email, Timestamp.from(since));
	}

	public int countByRequester(String requesterKey, Instant since) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM ziper.auth_codes WHERE requester_key = ? AND requested_at >= ?", Integer.class,
				requesterKey, Timestamp.from(since));
	}

	public Instant lastRequestedAt(String email) {
		List<Instant> values = jdbc.query(
				"SELECT requested_at FROM ziper.auth_codes WHERE email = ? "
						+ "ORDER BY requested_at DESC, id DESC LIMIT 1",
				(result, row) -> result.getObject(1, OffsetDateTime.class).toInstant(), email);
		return values.isEmpty() ? null : values.getFirst();
	}

	public AuthCode latestByFlow(UUID flowId) {
		List<AuthCode> values = jdbc.query(
				"SELECT id, email, code_hash, expired_at, "
						+ "verified_at IS NOT NULL AS verified, consumed_at IS NOT NULL AS consumed "
						+ "FROM ziper.auth_codes WHERE flow_id = ? ORDER BY id DESC LIMIT 1",
				(result, row) -> new AuthCode(result.getLong("id"), result.getString("email"),
						result.getString("code_hash"), result.getObject("expired_at", OffsetDateTime.class).toInstant(),
						result.getBoolean("verified"), result.getBoolean("consumed")),
				flowId);
		return values.isEmpty() ? null : values.getFirst();
	}

	public int failuresByFlow(UUID flowId) {
		return jdbc.queryForObject("SELECT COALESCE(SUM(failed_attempts), 0) FROM ziper.auth_codes WHERE flow_id = ?",
				Integer.class, flowId);
	}

	public void insert(UUID flowId, String email, String codeHash, String requesterKey, Instant requestedAt,
			Instant expiredAt) {
		jdbc.update(
				"INSERT INTO ziper.auth_codes (flow_id, email, code_hash, requester_key, requested_at, expired_at) "
						+ "VALUES (?, ?, ?, ?, ?, ?)",
				flowId, email, codeHash, requesterKey, Timestamp.from(requestedAt), Timestamp.from(expiredAt));
	}

	public void incrementFailure(long id) {
		jdbc.update("UPDATE ziper.auth_codes SET failed_attempts = failed_attempts + 1 WHERE id = ?", id);
	}

	public void markVerified(long id, Instant at) {
		jdbc.update("UPDATE ziper.auth_codes SET verified_at = ? WHERE id = ?", Timestamp.from(at), id);
	}

	public record AuthCode(long id, String email, String codeHash, Instant expiredAt, boolean verified,
			boolean consumed) {
	}
}
