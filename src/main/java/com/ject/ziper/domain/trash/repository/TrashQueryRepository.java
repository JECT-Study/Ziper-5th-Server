package com.ject.ziper.domain.trash.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrashQueryRepository {
	private final JdbcTemplate jdbc;

	public TrashQueryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<TrashProductRow> findUnexpiredProducts(Long userId) {
		return jdbc.query("""
				WITH query_time AS (SELECT CURRENT_TIMESTAMP AS now_at)
				SELECT pc.id AS card_id,
				       pc.shop,
				       pc.product_title,
				       pc.price,
				       pc.image_url,
				       pc.image_file_id,
				       CEIL(EXTRACT(EPOCH FROM (pc.deleted_at + INTERVAL '30 days' - query_time.now_at))
				           / 86400.0)::integer AS days_remaining
				FROM ziper.product_cards pc
				CROSS JOIN query_time
				WHERE pc.user_id = ?
				  AND pc.is_deleted = TRUE
				  AND pc.deleted_at > query_time.now_at - INTERVAL '30 days'
				ORDER BY pc.deleted_at DESC, pc.id DESC
				""",
				(rs, rowNum) -> new TrashProductRow(rs.getLong("card_id"), rs.getString("shop"),
						rs.getString("product_title"), rs.getLong("price"), rs.getString("image_url"),
						rs.getObject("image_file_id", Long.class), rs.getInt("days_remaining")),
				userId);
	}

	public record TrashProductRow(Long cardId, String shop, String productTitle, Long price, String imageUrl,
			Long imageFileId, int daysRemaining) {
	}
}
