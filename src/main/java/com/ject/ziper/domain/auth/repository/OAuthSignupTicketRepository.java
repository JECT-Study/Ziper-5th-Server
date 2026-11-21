package com.ject.ziper.domain.auth.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ject.ziper.global.security.oauth.OAuthProvider;
import com.ject.ziper.global.security.oauth.OAuthUserPrincipal;

@Repository
public class OAuthSignupTicketRepository {
	public static final int DELETE_BATCH_SIZE = 100;

	private final JdbcTemplate jdbc;

	public OAuthSignupTicketRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void insert(String tokenHash, OAuthUserPrincipal principal, Instant expiresAt) {
		jdbc.update("""
				INSERT INTO ziper.oauth_signup_tickets (token_hash, provider, provider_id, email, expires_at)
				VALUES (?, ?, ?, ?, ?)
				""", tokenHash, principal.provider().name(), principal.providerId(), principal.email(),
				Timestamp.from(expiresAt));
	}

	public Optional<OAuthUserPrincipal> consume(String tokenHash, Instant now) {
		return jdbc.query("""
				DELETE FROM ziper.oauth_signup_tickets WHERE token_hash = ? AND expires_at > ?
				RETURNING provider, provider_id, email
				""",
				(result, row) -> new OAuthUserPrincipal(OAuthProvider.valueOf(result.getString("provider")),
						result.getString("provider_id"), result.getString("email")),
				tokenHash, Timestamp.from(now)).stream().findFirst();
	}

	public int deleteExpired(Instant now) {
		return jdbc.update("""
				DELETE FROM ziper.oauth_signup_tickets WHERE token_hash IN (
				    SELECT token_hash FROM ziper.oauth_signup_tickets WHERE expires_at <= ?
				    ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED
				)
				""", Timestamp.from(now), DELETE_BATCH_SIZE);
	}

}
