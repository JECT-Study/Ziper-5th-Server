package com.ject.ziper.domain.product.entity;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_card_tags", schema = "ziper")
public class ProductCardTag {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "card_id", nullable = false)
	private Long cardId;

	@Column(name = "tag_id", nullable = false)
	private Long tagId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ProductCardTag() {
	}

	private ProductCardTag(Long cardId, Long tagId, Instant createdAt) {
		this.cardId = Objects.requireNonNull(cardId);
		this.tagId = Objects.requireNonNull(tagId);
		this.createdAt = Objects.requireNonNull(createdAt);
	}

	public static ProductCardTag create(Long cardId, Long tagId, Instant createdAt) {
		return new ProductCardTag(cardId, tagId, createdAt);
	}

	public Long getId() {
		return id;
	}

	public Long getCardId() {
		return cardId;
	}

	public Long getTagId() {
		return tagId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
