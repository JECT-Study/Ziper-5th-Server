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
@Table(name = "product_links", schema = "ziper")
public class ProductLink {
	private static final int MAX_SHOP_LENGTH = 100;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "canonical_url", nullable = false, unique = true, length = 2048)
	private String canonicalUrl;

	@Column(name = "shop", length = MAX_SHOP_LENGTH)
	private String shop;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 32)
	private ProductLinkStatus status;

	@Column(name = "checked_at")
	private Instant checkedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "check_source", length = 32)
	private ProductLinkCheckSource checkSource;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ProductLink() {
	}

	private ProductLink(String canonicalUrl, String shop, Instant now) {
		this.canonicalUrl = canonicalUrl;
		this.shop = normalizeShop(shop);
		this.status = ProductLinkStatus.UNKNOWN;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static ProductLink create(String canonicalUrl, String shop, Instant now) {
		return new ProductLink(canonicalUrl, shop, now);
	}

	public void updateShopIfBlank(String shop, Instant now) {
		String normalized = normalizeShop(shop);
		if (this.shop == null && normalized != null) {
			this.shop = normalized;
			this.updatedAt = now;
		}
	}

	public void applyCheckResult(ProductLinkStatus status, Instant checkedAt, ProductLinkCheckSource checkSource,
			Instant now) {
		this.status = status;
		this.checkedAt = checkedAt;
		this.checkSource = checkSource;
		this.updatedAt = now;
	}

	private static String normalizeShop(String shop) {
		if (shop == null || shop.isBlank()) {
			return null;
		}
		String trimmed = shop.trim();
		return trimmed.length() > MAX_SHOP_LENGTH ? trimmed.substring(0, MAX_SHOP_LENGTH) : trimmed;
	}

	public Long getId() {
		return id;
	}

	public String getCanonicalUrl() {
		return canonicalUrl;
	}

	public String getShop() {
		return shop;
	}

	public ProductLinkStatus getStatus() {
		return status;
	}

	public Instant getCheckedAt() {
		return checkedAt;
	}

	public ProductLinkCheckSource getCheckSource() {
		return checkSource;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
