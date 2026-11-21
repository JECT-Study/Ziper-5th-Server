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

	@Column(name = "email", nullable = true, unique = true, length = 255)
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

	@Column(name = "terms_agreed_at")
	private Instant termsAgreedAt;

	@Column(name = "privacy_agreed_at")
	private Instant privacyAgreedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_logged_at")
	private Instant lastLoggedAt;

	@Column(name = "oauth_connect", unique = true)
	private Long oauthConnectId;

	protected User() {
	}

	private User(String email, String passwordHash, String nickname, boolean marketingAgreed, Long oauthConnectId,
			Instant now) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.nickname = nickname;
		this.status = UserStatus.ACTIVE;
		this.marketingAgreed = marketingAgreed;
		this.marketingAgreedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
		this.termsAgreedAt = now;
		this.privacyAgreedAt = now;
		this.createdAt = now;
		this.lastLoggedAt = now;
		this.oauthConnectId = oauthConnectId;
	}

	public static User createOAuthUser(String email, String nickname, Long oauthConnectId, boolean marketingAgreed,
			Instant now) {
		return new User(email, null, nickname, marketingAgreed, oauthConnectId, now);
	}

	public static User createEmailUser(String email, String passwordHash, String nickname, boolean marketingAgreed,
			Instant now) {
		return new User(email, passwordHash, nickname, marketingAgreed, null, now);
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

	public String getPasswordHash() {
		return passwordHash;
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

	public boolean isMarketingAgreed() {
		return marketingAgreed;
	}

	public Instant getMarketingAgreedAt() {
		return marketingAgreedAt.toInstant(ZoneOffset.UTC);
	}

	public Instant getTermsAgreedAt() {
		return termsAgreedAt;
	}

	public Instant getPrivacyAgreedAt() {
		return privacyAgreedAt;
	}

	public Long getOauthConnectId() {
		return oauthConnectId;
	}
}
