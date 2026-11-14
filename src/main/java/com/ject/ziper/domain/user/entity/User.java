package com.ject.ziper.domain.user.entity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users", schema = "ziper")
public class User {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "email", nullable = false, unique = true, length = 255)
	private String email;

	@Column(name = "password_hash", length = 255)
	private String passwordHash;

	@Column(name = "nickname", nullable = false, unique = true, length = 10)
	private String nickname;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 64)
	private UserStatus status;

	@Column(name = "marketing_agreed", nullable = false)
	private boolean marketingAgreed;

	@Column(name = "marketing_agreed_at", nullable = false)
	private LocalDateTime marketingAgreedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_logged_at")
	private Instant lastLoggedAt;

	@Column(name = "oauth_connect", unique = true)
	private Long oauthConnectId;

	protected User() {
	}

	private User(String email, String nickname, Long oauthConnectId, Instant now) {
		this.email = email;
		this.nickname = nickname;
		this.status = UserStatus.ACTIVE;
		this.marketingAgreed = false;
		this.marketingAgreedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
		this.createdAt = now;
		this.lastLoggedAt = now;
		this.oauthConnectId = oauthConnectId;
	}

	public static User createOAuthUser(String email, String nickname, Long oauthConnectId, Instant now) {
		return new User(email, nickname, oauthConnectId, now);
	}

	public void recordLogin(Instant now) {
		this.lastLoggedAt = now;
	}

	public Long getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getNickname() {
		return nickname;
	}

	public UserStatus getStatus() {
		return status;
	}

	public Instant getLastLoggedAt() {
		return lastLoggedAt;
	}

	public Long getOauthConnectId() {
		return oauthConnectId;
	}
}
