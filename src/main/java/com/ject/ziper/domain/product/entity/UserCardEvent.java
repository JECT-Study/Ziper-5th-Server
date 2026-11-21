package com.ject.ziper.domain.product.entity;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_card_events", schema = "ziper")
public class UserCardEvent {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "card_id", nullable = false)
	private Long cardId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 64)
	private UserCardEventType eventType;

	@Column(name = "viewed_at", nullable = false)
	private Instant viewedAt;

	protected UserCardEvent() {
	}

	private UserCardEvent(Long userId, Long cardId, UserCardEventType eventType, Instant viewedAt) {
		this.userId = Objects.requireNonNull(userId);
		this.cardId = Objects.requireNonNull(cardId);
		this.eventType = Objects.requireNonNull(eventType);
		this.viewedAt = Objects.requireNonNull(viewedAt);
	}

	public static UserCardEvent create(Long userId, Long cardId, UserCardEventType eventType, Instant viewedAt) {
		return new UserCardEvent(userId, cardId, eventType, viewedAt);
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public Long getCardId() {
		return cardId;
	}

	public UserCardEventType getEventType() {
		return eventType;
	}

	public Instant getViewedAt() {
		return viewedAt;
	}
}
