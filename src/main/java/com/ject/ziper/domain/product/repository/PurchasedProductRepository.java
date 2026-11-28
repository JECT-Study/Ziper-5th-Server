package com.ject.ziper.domain.product.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ject.ziper.domain.product.dto.PurchasedProductCursor;
import com.ject.ziper.domain.product.dto.PurchasedProductSort;
import com.ject.ziper.domain.product.entity.FallbackIcon;
import com.ject.ziper.domain.tag.dto.TagResponse;

@Repository
public class PurchasedProductRepository {
	private final NamedParameterJdbcTemplate jdbc;

	public PurchasedProductRepository(JdbcTemplate jdbc) {
		this.jdbc = new NamedParameterJdbcTemplate(jdbc);
	}

	public int countActiveBoards(Long userId, Long cardId) {
		// V13의 연결 소프트 삭제 컬럼이 배포되기 전과 후에 모두 활성 연결만 센다.
		Integer count = jdbc.queryForObject("""
				SELECT COUNT(*)
				FROM ziper.product_board_card pbc
				JOIN ziper.product_boards pb ON pb.id = pbc.board_id
				WHERE pbc.card_id = :cardId
				  AND COALESCE((to_jsonb(pbc)->>'is_deleted')::boolean, FALSE) = FALSE
				  AND pb.user_id = :userId AND pb.is_deleted = FALSE
				""", new MapSqlParameterSource().addValue("cardId", cardId).addValue("userId", userId), Integer.class);
		return count == null ? 0 : count;
	}

	public int countPurchased(Long userId) {
		Integer count = jdbc.queryForObject("""
				SELECT COUNT(*) FROM ziper.product_cards
				WHERE user_id = :userId AND is_deleted = FALSE AND status = 'PURCHASED'
				""", new MapSqlParameterSource("userId", userId), Integer.class);
		return count == null ? 0 : count;
	}

	public List<PurchasedRow> findPurchased(Long userId, PurchasedProductSort sort, PurchasedProductCursor cursor,
			int limit) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("userId", userId).addValue("limit", limit);
		StringBuilder sql = new StringBuilder("""
				SELECT pc.id, pc.product_url, pc.shop, pc.product_title, pc.price,
				       pc.image_url, pc.image_file_id, pc.fallback_icon, pc.created_at,
				       tag.tag_id, tag.title AS tag_title, tag.is_default AS tag_is_default
				FROM ziper.product_cards pc
				LEFT JOIN LATERAL (
				    SELECT t.id AS tag_id, t.title, t.is_default
				    FROM ziper.product_card_tags pct
				    JOIN ziper.tags t ON t.id = pct.tag_id
				    WHERE pct.card_id = pc.id
				    ORDER BY t.is_default DESC, t.id ASC
				    LIMIT 1
				) tag ON TRUE
				WHERE pc.user_id = :userId AND pc.is_deleted = FALSE AND pc.status = 'PURCHASED'
				""");
		if (cursor != null) {
			params.addValue("createdAt", cursor.createdAt().atOffset(ZoneOffset.UTC))
					.addValue("cardId", cursor.cardId()).addValue("price", cursor.price());
			sql.append(" AND ").append(seekClause(sort)).append('\n');
		}
		sql.append("ORDER BY ").append(orderByClause(sort)).append("\nLIMIT :limit");
		return jdbc.query(sql.toString(), params, (rs, rowNum) -> {
			OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
			Long tagId = rs.getObject("tag_id", Long.class);
			TagResponse tag = tagId == null
					? null
					: new TagResponse(tagId, rs.getString("tag_title"), rs.getBoolean("tag_is_default"));
			return new PurchasedRow(rs.getLong("id"), rs.getString("product_url"), rs.getString("shop"),
					rs.getString("product_title"), rs.getObject("price", Long.class), rs.getString("image_url"),
					rs.getObject("image_file_id", Long.class),
					FallbackIcon.fromValue(rs.getString("fallback_icon")).orElse(null), tag, createdAt.toInstant());
		});
	}

	private static String seekClause(PurchasedProductSort sort) {
		String older = "(pc.created_at, pc.id) < (:createdAt, :cardId)";
		return switch (sort) {
			case RECENT -> older;
			case OLDEST -> "(pc.created_at, pc.id) > (:createdAt, :cardId)";
			case PRICE_ASC -> "(pc.price > :price OR (pc.price = :price AND " + older + "))";
			case PRICE_DESC -> "(pc.price < :price OR (pc.price = :price AND " + older + "))";
		};
	}

	private static String orderByClause(PurchasedProductSort sort) {
		return switch (sort) {
			case RECENT -> "pc.created_at DESC, pc.id DESC";
			case OLDEST -> "pc.created_at ASC, pc.id ASC";
			case PRICE_ASC -> "pc.price ASC NULLS LAST, pc.created_at DESC, pc.id DESC";
			case PRICE_DESC -> "pc.price DESC NULLS LAST, pc.created_at DESC, pc.id DESC";
		};
	}

	public record PurchasedRow(Long cardId, String productUrl, String shop, String productTitle, Long price,
			String imageUrl, Long imageFileId, FallbackIcon fallbackIcon, TagResponse tag, Instant createdAt) {
	}
}
