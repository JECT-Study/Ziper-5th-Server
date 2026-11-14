package com.ject.ziper.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.ject.ziper.global.security.oauth.OAuthProvider;

@Entity
@Table(name = "oauth_users", schema = "ziper")
public class OAuthUser {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", nullable = false, length = 255)
	private OAuthProvider provider;

	@Column(name = "provider_id", nullable = false, length = 255)
	private String providerId;

	protected OAuthUser() {
	}

	private OAuthUser(OAuthProvider provider, String providerId) {
		this.provider = provider;
		this.providerId = providerId;
	}

	public static OAuthUser create(OAuthProvider provider, String providerId) {
		return new OAuthUser(provider, providerId);
	}

	public Long getId() {
		return id;
	}

	public OAuthProvider getProvider() {
		return provider;
	}

	public String getProviderId() {
		return providerId;
	}
}
