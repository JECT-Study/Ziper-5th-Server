package com.ject.ziper.domain.product.link.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ject.ziper.domain.product.link.entity.ProductLinkCheckSource;
import com.ject.ziper.domain.product.link.entity.ProductLinkStatus;

@Repository
public class ProductCardLinkJdbcRepository {
	private final JdbcTemplate jdbc;

	public ProductCardLinkJdbcRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<ProductCardLinkSnapshot> findOwnedActiveCard(Long cardId, Long userId) {
		List<ProductCardLinkSnapshot> rows = jdbc.query(
				"SELECT id, user_id, product_url, link_id, link_status, link_checked_at, link_check_source "
						+ "FROM ziper.product_cards " + "WHERE id = ? AND user_id = ? AND is_deleted = FALSE",
				(result, rowNum) -> {
					Long linkId = result.getObject("link_id", Long.class);
					OffsetDateTime checkedAt = result.getObject("link_checked_at", OffsetDateTime.class);
					ProductLinkStatus status = ProductLinkStatus.fromValue(result.getString("link_status"))
							.orElse(ProductLinkStatus.UNKNOWN);
					ProductLinkCheckSource source = ProductLinkCheckSource
							.fromValue(result.getString("link_check_source")).orElse(null);
					return new ProductCardLinkSnapshot(result.getLong("id"), result.getLong("user_id"),
							result.getString("product_url"), linkId, status,
							checkedAt == null ? null : checkedAt.toInstant(), source);
				}, cardId, userId);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}

	public void attachLinkId(Long cardId, Long linkId, Instant updatedAt) {
		jdbc.update("UPDATE ziper.product_cards SET link_id = ?, updated_at = ? WHERE id = ? AND link_id IS NULL",
				linkId, Timestamp.from(updatedAt), cardId);
	}

	public int updateCardLinkStatus(Long cardId, Long userId, Long linkId, ProductLinkStatus status, Instant checkedAt,
			ProductLinkCheckSource source, Instant updatedAt) {
		return jdbc.update("UPDATE ziper.product_cards "
				+ "SET link_id = ?, link_status = ?, link_checked_at = ?, link_check_source = ?, updated_at = ? "
				+ "WHERE id = ? AND user_id = ? AND is_deleted = FALSE", linkId, status.name(),
				Timestamp.from(checkedAt), source.name(), Timestamp.from(updatedAt), cardId, userId);
	}

	public int propagateLinkStatus(Long linkId, Long excludeCardId, ProductLinkStatus status, Instant checkedAt,
			ProductLinkCheckSource source, Instant updatedAt) {
		if (excludeCardId == null) {
			return jdbc.update(
					"UPDATE ziper.product_cards "
							+ "SET link_status = ?, link_checked_at = ?, link_check_source = ?, updated_at = ? "
							+ "WHERE link_id = ? AND (link_checked_at IS NULL OR link_checked_at < ?)",
					status.name(), Timestamp.from(checkedAt), source.name(), Timestamp.from(updatedAt), linkId,
					Timestamp.from(checkedAt));
		}
		return jdbc.update(
				"UPDATE ziper.product_cards "
						+ "SET link_status = ?, link_checked_at = ?, link_check_source = ?, updated_at = ? "
						+ "WHERE link_id = ? AND id <> ? AND (link_checked_at IS NULL OR link_checked_at < ?)",
				status.name(), Timestamp.from(checkedAt), source.name(), Timestamp.from(updatedAt), linkId,
				excludeCardId, Timestamp.from(checkedAt));
	}

	public record ProductCardLinkSnapshot(Long cardId, Long userId, String productUrl, Long linkId,
			ProductLinkStatus linkStatus, Instant linkCheckedAt, ProductLinkCheckSource linkCheckSource) {
	}
}
