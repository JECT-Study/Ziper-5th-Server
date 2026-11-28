package com.ject.ziper.domain.home.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ject.ziper.domain.product.entity.FallbackIcon;
import com.ject.ziper.domain.product.entity.ProductImageStatus;
import com.ject.ziper.domain.product.link.entity.ProductLinkCheckSource;
import com.ject.ziper.domain.product.link.entity.ProductLinkStatus;

@Repository
public class HomeQueryRepository {
	private final JdbcTemplate jdbc;

	public HomeQueryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public long countRediscoveryCandidates(long userId, Instant now) {
		Long count = jdbc.queryForObject("""
				SELECT COUNT(*)
				FROM ziper.product_cards
				WHERE user_id = ?
				  AND is_deleted = FALSE
				  AND status = 'PLANNED'
				  AND link_status NOT IN ('REMOVED', 'SOLD_OUT')
				  AND next_rediscovery_at <= ?
				""", Long.class, userId, Timestamp.from(now));
		return count != null ? count : 0L;
	}

	public List<BoardSummaryRow> findActiveBoardsWithProductCount(long userId) {
		return jdbc.query("""
				SELECT pb.id,
				       pb.name,
				       pb.modifiable,
				       pb.created_at,
				       COUNT(pc.id) AS product_count
				FROM ziper.product_boards pb
				LEFT JOIN ziper.product_board_card pbc
				       ON pbc.board_id = pb.id AND pbc.is_deleted = FALSE
				LEFT JOIN ziper.product_cards pc
				       ON pc.id = pbc.card_id
				      AND pc.user_id = pb.user_id
				      AND pc.is_deleted = FALSE
				      AND pc.status = 'PLANNED'
				WHERE pb.user_id = ?
				  AND pb.is_deleted = FALSE
				GROUP BY pb.id, pb.name, pb.modifiable, pb.created_at
				ORDER BY pb.created_at DESC, pb.id DESC
				""",
				(result, rowNum) -> new BoardSummaryRow(result.getLong("id"), result.getString("name"),
						result.getBoolean("modifiable"), result.getLong("product_count"),
						toInstant(result.getObject("created_at", OffsetDateTime.class))),
				userId);
	}

	public List<BoardPreviewImageRow> findBoardPreviewImages(long userId) {
		return jdbc.query("""
				SELECT ranked.board_id,
				       ranked.card_id,
				       ranked.image_url,
				       ranked.image_file_id,
				       ranked.fallback_icon
				FROM (
				    SELECT pbc.board_id,
				           pc.id AS card_id,
				           pc.image_url,
				           pc.image_file_id,
				           pc.fallback_icon,
				           ROW_NUMBER() OVER (
				               PARTITION BY pbc.board_id
				               ORDER BY pbc.created_at DESC, pbc.id DESC
				           ) AS rn
				    FROM ziper.product_boards pb
				    JOIN ziper.product_board_card pbc
				      ON pbc.board_id = pb.id AND pbc.is_deleted = FALSE
				    JOIN ziper.product_cards pc
				      ON pc.id = pbc.card_id
				    WHERE pb.user_id = ?
				      AND pb.is_deleted = FALSE
				      AND pc.user_id = ?
				      AND pc.is_deleted = FALSE
				      AND pc.status = 'PLANNED'
				) ranked
				WHERE ranked.rn <= 3
				ORDER BY ranked.board_id ASC, ranked.rn ASC
				""",
				(result, rowNum) -> new BoardPreviewImageRow(result.getLong("board_id"), result.getLong("card_id"),
						result.getString("image_url"), result.getObject("image_file_id", Long.class),
						FallbackIcon.fromValue(result.getString("fallback_icon")).orElse(null)),
				userId, userId);
	}

	public List<RecentProductRow> findRecentProducts(long userId, int recentLimit) {
		return jdbc.query("""
				SELECT pc.id,
				       pc.shop,
				       pc.product_title,
				       pc.price,
				       pc.image_url,
				       pc.image_file_id,
				       pc.fallback_icon,
				       pc.image_status,
				       pc.link_status,
				       pc.link_checked_at,
				       pc.link_check_source,
				       pc.created_at
				FROM ziper.product_cards pc
				WHERE pc.user_id = ?
				  AND pc.is_deleted = FALSE
				  AND pc.status = 'PLANNED'
				ORDER BY pc.created_at DESC, pc.id DESC
				LIMIT ?
				""",
				(result, rowNum) -> new RecentProductRow(result.getLong("id"), result.getString("shop"),
						result.getString("product_title"), result.getLong("price"), result.getString("image_url"),
						result.getObject("image_file_id", Long.class),
						FallbackIcon.fromValue(result.getString("fallback_icon")).orElse(null),
						ProductImageStatus.fromValue(result.getString("image_status")).orElse(ProductImageStatus.NONE),
						ProductLinkStatus.fromValue(result.getString("link_status")).orElse(ProductLinkStatus.UNKNOWN),
						toInstant(result.getObject("link_checked_at", OffsetDateTime.class)),
						ProductLinkCheckSource.fromValue(result.getString("link_check_source")).orElse(null),
						toInstant(result.getObject("created_at", OffsetDateTime.class))),
				userId, recentLimit);
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	public record BoardSummaryRow(Long boardId, String name, boolean modifiable, long productCount, Instant createdAt) {
	}

	public record BoardPreviewImageRow(Long boardId, Long cardId, String imageUrl, Long imageFileId,
			FallbackIcon fallbackIcon) {
	}

	public record RecentProductRow(Long cardId, String shop, String productTitle, Long price, String imageUrl,
			Long imageFileId, FallbackIcon fallbackIcon, ProductImageStatus imageStatus, ProductLinkStatus linkStatus,
			Instant linkCheckedAt, ProductLinkCheckSource linkCheckSource, Instant createdAt) {
	}
}
