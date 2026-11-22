package com.ject.ziper.domain.product.link.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "product_link_reports", schema = "ziper")
public class ProductLinkReport {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "link_id", nullable = false)
	private Long linkId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private ProductLinkStatus status;

	@Column(name = "observed_at", nullable = false)
	private Instant observedAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ProductLinkReport() {
	}

	private ProductLinkReport(Long linkId, Long userId, ProductLinkStatus status, Instant observedAt, Instant now) {
		this.linkId = linkId;
		this.userId = userId;
		this.status = status;
		this.observedAt = observedAt;
		this.createdAt = now;
	}

	public static ProductLinkReport create(Long linkId, Long userId, ProductLinkStatus status, Instant observedAt,
			Instant now) {
		return new ProductLinkReport(linkId, userId, status, observedAt, now);
	}

	public Long getId() {
		return id;
	}

	public Long getLinkId() {
		return linkId;
	}

	public Long getUserId() {
		return userId;
	}

	public ProductLinkStatus getStatus() {
		return status;
	}

	public Instant getObservedAt() {
		return observedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
