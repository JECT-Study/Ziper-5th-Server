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

	public EmailFailures failuresByEmail(String email) {
		List<EmailFailures> values = jdbc
				.query("SELECT failed_attempts, last_failed_at, locked_until FROM ziper.email_verification_failures "
						+ "WHERE email = ?", (result, row) -> {
							OffsetDateTime lockedUntil = result.getObject("locked_until", OffsetDateTime.class);
							return new EmailFailures(result.getInt("failed_attempts"),
									result.getObject("last_failed_at", OffsetDateTime.class).toInstant(),
									lockedUntil == null ? null : lockedUntil.toInstant());
						}, email);
		return values.isEmpty() ? null : values.getFirst();
	}

	public void saveFailures(String email, int failedAttempts, Instant lastFailedAt, Instant lockedUntil) {
		jdbc.update(
				"INSERT INTO ziper.email_verification_failures (email, failed_attempts, last_failed_at, locked_until) "
						+ "VALUES (?, ?, ?, ?) ON CONFLICT (email) DO UPDATE SET failed_attempts = EXCLUDED.failed_attempts, "
						+ "last_failed_at = EXCLUDED.last_failed_at, locked_until = EXCLUDED.locked_until",
				email, failedAttempts, Timestamp.from(lastFailedAt),
				lockedUntil == null ? null : Timestamp.from(lockedUntil));
	}

	public void clearFailures(String email) {
		jdbc.update("DELETE FROM ziper.email_verification_failures WHERE email = ?", email);
	}

	public record EmailFailures(int failedAttempts, Instant lastFailedAt, Instant lockedUntil) {
	}

	public record AuthCode(long id, String email, String codeHash, Instant expiredAt, boolean verified,
			boolean consumed) {
	}
}
