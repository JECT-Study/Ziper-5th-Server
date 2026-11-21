package com.ject.ziper.domain.tag.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tags", schema = "ziper")
public class Tag {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	@Column(name = "title", nullable = false, length = 64)
	private String title;

	@Column(name = "is_default", nullable = false)
	private boolean defaultTag;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Tag() {
	}

	private Tag(Long userId, String title, boolean defaultTag, Instant createdAt) {
		this.userId = userId;
		this.title = title;
		this.defaultTag = defaultTag;
		this.createdAt = createdAt;
	}

	public static Tag createCustom(Long userId, String title, Instant createdAt) {
		return new Tag(userId, title, false, createdAt);
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getTitle() {
		return title;
	}

	public boolean isDefault() {
		return defaultTag;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
