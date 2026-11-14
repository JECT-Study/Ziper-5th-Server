package com.ject.ziper.domain.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "sessions", schema = "ziper")
public class Session {
	private static final int MAX_USER_AGENT_LENGTH = 255;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "user_agent", length = MAX_USER_AGENT_LENGTH)
	private String userAgent;

	@Column(name = "token_hash", nullable = false, length = 255)
	private String tokenHash;

	protected Session() {
	}

	private Session(Long userId, String userAgent, String tokenHash) {
		this.userId = userId;
		this.userAgent = normalizeUserAgent(userAgent);
		this.tokenHash = tokenHash;
	}

	public static Session create(Long userId, String userAgent, String tokenHash) {
		return new Session(userId, userAgent, tokenHash);
	}

	public void rotate(String newTokenHash, String newUserAgent) {
		this.tokenHash = newTokenHash;
		this.userAgent = normalizeUserAgent(newUserAgent);
	}

	private static String normalizeUserAgent(String userAgent) {
		if (userAgent == null || userAgent.isBlank()) {
			return null;
		}
		String trimmed = userAgent.trim();
		return trimmed.length() > MAX_USER_AGENT_LENGTH ? trimmed.substring(0, MAX_USER_AGENT_LENGTH) : trimmed;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public String getTokenHash() {
		return tokenHash;
	}
}
