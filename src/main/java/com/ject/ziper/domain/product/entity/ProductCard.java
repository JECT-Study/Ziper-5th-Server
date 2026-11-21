package com.ject.ziper.domain.product.entity;

import java.time.Duration;
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

import com.ject.ziper.domain.product.link.entity.ProductLink;
import com.ject.ziper.domain.product.link.entity.ProductLinkCheckSource;
import com.ject.ziper.domain.product.link.entity.ProductLinkStatus;

@Entity
@Table(name = "product_cards", schema = "ziper")
public class ProductCard {
	public static final Duration INITIAL_REDISCOVERY_INTERVAL = Duration.ofDays(14);
	public static final Duration STAGE_1_REDISCOVERY_INTERVAL = Duration.ofDays(30);
	public static final Duration STAGE_2_REDISCOVERY_INTERVAL = Duration.ofDays(90);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "product_url", nullable = false, length = 2048)
	private String productUrl;

	@Column(name = "shared_url", length = 2048)
	private String sharedUrl;

	@Column(name = "shop", length = 100)
	private String shop;

	@Column(name = "product_title", nullable = false, length = 255)
	private String productTitle;

	@Column(name = "price", nullable = false)
	private Long price;

	@Column(name = "memo", columnDefinition = "TEXT")
	private String memo;

	@Column(name = "image_url", columnDefinition = "TEXT")
	private String imageUrl;

	@Column(name = "image_file_id")
	private Long imageFileId;

	@Enumerated(EnumType.STRING)
	@Column(name = "fallback_icon", length = 64)
	private FallbackIcon fallbackIcon;

	@Enumerated(EnumType.STRING)
	@Column(name = "metadata_source", nullable = false, length = 32)
	private MetadataSource metadataSource;

	@Enumerated(EnumType.STRING)
	@Column(name = "image_status", nullable = false, length = 32)
	private ProductImageStatus imageStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 64)
	private ProductCardStatus status;

	@Column(name = "link_id")
	private Long linkId;

	@Enumerated(EnumType.STRING)
	@Column(name = "link_status", nullable = false, length = 32)
	private ProductLinkStatus linkStatus;

	@Column(name = "link_checked_at")
	private Instant linkCheckedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "link_check_source", length = 32)
	private ProductLinkCheckSource linkCheckSource;

	@Column(name = "rediscovery_stage", nullable = false)
	private int rediscoveryStage;

	@Column(name = "last_interacted_at", nullable = false)
	private Instant lastInteractedAt;

	@Column(name = "next_rediscovery_at", nullable = false)
	private Instant nextRediscoveryAt;

	@Column(name = "is_deleted", nullable = false)
	private boolean deleted;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ProductCard() {
	}

	private ProductCard(Long userId, String productUrl, String sharedUrl, String shop, String productTitle, Long price,
			String memo, String imageUrl, Long imageFileId, FallbackIcon fallbackIcon, MetadataSource metadataSource,
			ProductImageStatus imageStatus, ProductLink link, Instant now) {
		this.userId = Objects.requireNonNull(userId);
		this.productUrl = Objects.requireNonNull(productUrl);
		this.sharedUrl = sharedUrl;
		this.shop = shop;
		this.productTitle = Objects.requireNonNull(productTitle);
		this.price = Objects.requireNonNull(price);
		this.memo = memo;
		this.imageUrl = imageUrl;
		this.imageFileId = imageFileId;
		this.fallbackIcon = fallbackIcon;
		this.metadataSource = Objects.requireNonNull(metadataSource);
		this.imageStatus = Objects.requireNonNull(imageStatus);
		this.status = ProductCardStatus.PLANNED;
		if (link != null) {
			this.linkId = link.getId();
			this.linkStatus = link.getStatus() != null ? link.getStatus() : ProductLinkStatus.UNKNOWN;
			this.linkCheckedAt = link.getCheckedAt();
			this.linkCheckSource = link.getCheckSource();
		} else {
			this.linkStatus = ProductLinkStatus.UNKNOWN;
		}
		this.rediscoveryStage = 0;
		this.lastInteractedAt = Objects.requireNonNull(now);
		this.nextRediscoveryAt = now.plus(INITIAL_REDISCOVERY_INTERVAL);
		this.deleted = false;
		this.deletedAt = null;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static ProductCard create(Long userId, String productUrl, String sharedUrl, String shop, String productTitle,
			Long price, String memo, String imageUrl, Long imageFileId, FallbackIcon fallbackIcon,
			MetadataSource metadataSource, ProductImageStatus imageStatus, ProductLink link, Instant now) {
		return new ProductCard(userId, productUrl, sharedUrl, shop, productTitle, price, memo, imageUrl, imageFileId,
				fallbackIcon, metadataSource, imageStatus, link, now);
	}

	public void updateImageStatus(ProductImageStatus imageStatus, Instant now) {
		this.imageStatus = Objects.requireNonNull(imageStatus);
		this.updatedAt = Objects.requireNonNull(now);
	}

	public void updateDetails(String productTitle, Long price, String memo, String shop, String imageUrl,
			Long imageFileId, FallbackIcon fallbackIcon, ProductImageStatus imageStatus, Instant now) {
		this.productTitle = Objects.requireNonNull(productTitle);
		this.price = Objects.requireNonNull(price);
		this.memo = memo;
		this.shop = shop;
		this.imageUrl = imageUrl;
		this.imageFileId = imageFileId;
		this.fallbackIcon = fallbackIcon;
		this.imageStatus = Objects.requireNonNull(imageStatus);
		this.updatedAt = Objects.requireNonNull(now);
	}

	public void markPurchased(Instant now) {
		this.status = ProductCardStatus.PURCHASED;
		this.updatedAt = Objects.requireNonNull(now);
	}

	public void markPlanned(Instant now) {
		this.status = ProductCardStatus.PLANNED;
		this.updatedAt = Objects.requireNonNull(now);
	}

	public void softDelete(Instant now) {
		this.deleted = true;
		this.deletedAt = Objects.requireNonNull(now);
		this.updatedAt = now;
	}

	public void restore(Instant now) {
		this.deleted = false;
		this.deletedAt = null;
		this.status = ProductCardStatus.PLANNED;
		this.rediscoveryStage = 0;
		this.lastInteractedAt = Objects.requireNonNull(now);
		this.nextRediscoveryAt = now.plus(INITIAL_REDISCOVERY_INTERVAL);
		this.updatedAt = now;
	}

	public void resetRediscoveryCycle(Instant now) {
		this.rediscoveryStage = 0;
		this.lastInteractedAt = Objects.requireNonNull(now);
		this.nextRediscoveryAt = now.plus(INITIAL_REDISCOVERY_INTERVAL);
		this.updatedAt = now;
	}

	public void extendRediscoveryCycle(Instant now) {
		Objects.requireNonNull(now);
		if (this.rediscoveryStage == 0) {
			this.rediscoveryStage = 1;
			this.nextRediscoveryAt = now.plus(STAGE_1_REDISCOVERY_INTERVAL);
		} else {
			this.rediscoveryStage = 2;
			this.nextRediscoveryAt = now.plus(STAGE_2_REDISCOVERY_INTERVAL);
		}
		this.lastInteractedAt = now;
		this.updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getProductUrl() {
		return productUrl;
	}

	public String getSharedUrl() {
		return sharedUrl;
	}

	public String getShop() {
		return shop;
	}

	public String getProductTitle() {
		return productTitle;
	}

	public Long getPrice() {
		return price;
	}

	public String getMemo() {
		return memo;
	}

	public String getImageUrl() {
		return imageUrl;
	}

	public Long getImageFileId() {
		return imageFileId;
	}

	public FallbackIcon getFallbackIcon() {
		return fallbackIcon;
	}

	public MetadataSource getMetadataSource() {
		return metadataSource;
	}

	public ProductImageStatus getImageStatus() {
		return imageStatus;
	}

	public ProductCardStatus getStatus() {
		return status;
	}

	public Long getLinkId() {
		return linkId;
	}

	public ProductLinkStatus getLinkStatus() {
		return linkStatus;
	}

	public Instant getLinkCheckedAt() {
		return linkCheckedAt;
	}

	public ProductLinkCheckSource getLinkCheckSource() {
		return linkCheckSource;
	}

	public int getRediscoveryStage() {
		return rediscoveryStage;
	}

	public Instant getLastInteractedAt() {
		return lastInteractedAt;
	}

	public Instant getNextRediscoveryAt() {
		return nextRediscoveryAt;
	}

	public boolean isDeleted() {
		return deleted;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
