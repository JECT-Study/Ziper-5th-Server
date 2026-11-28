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
@Table(name = "product_board_card", schema = "ziper")
public class ProductBoardCard {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "board_id", nullable = false)
	private Long boardId;

	@Column(name = "card_id", nullable = false)
	private Long cardId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "is_deleted", nullable = false)
	private boolean deleted;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected ProductBoardCard() {
	}

	private ProductBoardCard(Long boardId, Long cardId, Instant createdAt) {
		this.boardId = Objects.requireNonNull(boardId);
		this.cardId = Objects.requireNonNull(cardId);
		this.createdAt = Objects.requireNonNull(createdAt);
		this.deleted = false;
		this.deletedAt = null;
	}

	public static ProductBoardCard create(Long boardId, Long cardId, Instant createdAt) {
		return new ProductBoardCard(boardId, cardId, createdAt);
	}

	public Long getId() {
		return id;
	}

	public Long getBoardId() {
		return boardId;
	}

	public Long getCardId() {
		return cardId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public boolean isDeleted() {
		return deleted;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public void softDelete(Instant now) {
		this.deleted = true;
		this.deletedAt = Objects.requireNonNull(now);
	}

	public void reactivate(Instant now) {
		this.deleted = false;
		this.deletedAt = null;
		this.createdAt = Objects.requireNonNull(now);
	}
}
