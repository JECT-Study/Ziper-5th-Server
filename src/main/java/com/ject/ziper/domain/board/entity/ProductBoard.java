package com.ject.ziper.domain.board.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_boards", schema = "ziper")
public class ProductBoard {
	public static final String DEFAULT_BOARD_NAME = "기본 보드";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "name", nullable = false, length = 255)
	private String name;

	@Column(name = "modifiable", nullable = false)
	private boolean modifiable;

	@Column(name = "is_deleted", nullable = false)
	private boolean deleted;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected ProductBoard() {
	}

	private ProductBoard(Long userId, String name, boolean modifiable) {
		this.userId = userId;
		this.name = name;
		this.modifiable = modifiable;
		this.deleted = false;
	}

	public static ProductBoard createDefault(Long userId) {
		return new ProductBoard(userId, DEFAULT_BOARD_NAME, false);
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getName() {
		return name;
	}

	public boolean isModifiable() {
		return modifiable;
	}

	public boolean isDeleted() {
		return deleted;
	}
}
