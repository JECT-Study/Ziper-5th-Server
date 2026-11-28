package com.ject.ziper.domain.board.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProductBoardCardCleanupRepository {
	private static final int BATCH_SIZE = 1000;

	private final JdbcTemplate jdbc;
	private final NamedParameterJdbcTemplate namedJdbc;

	public ProductBoardCardCleanupRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
		this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
	}

	public List<Long> findCardIdsByBoardId(Long boardId) {
		return jdbc.queryForList(
				"SELECT card_id FROM ziper.product_board_card WHERE board_id = ? AND is_deleted = FALSE", Long.class,
				boardId);
	}

	public int softDeleteBoardCardLinks(Long boardId, Instant deletedAt) {
		return jdbc.update("""
				UPDATE ziper.product_board_card
				SET is_deleted = TRUE, deleted_at = ?
				WHERE board_id = ? AND is_deleted = FALSE
				""", Timestamp.from(deletedAt), boardId);
	}

	public int softDeleteBoardCardLink(Long boardId, Long cardId, Instant deletedAt) {
		return jdbc.update("""
				UPDATE ziper.product_board_card
				SET is_deleted = TRUE, deleted_at = ?
				WHERE board_id = ? AND card_id = ? AND is_deleted = FALSE
				""", Timestamp.from(deletedAt), boardId, cardId);
	}

	public int softDeleteOrphanCards(Long userId, List<Long> cardIds, Instant deletedAt) {
		if (cardIds.isEmpty()) {
			return 0;
		}
		Timestamp timestamp = Timestamp.from(deletedAt);
		int updated = 0;
		for (int from = 0; from < cardIds.size(); from += BATCH_SIZE) {
			List<Long> chunk = cardIds.subList(from, Math.min(from + BATCH_SIZE, cardIds.size()));
			MapSqlParameterSource params = new MapSqlParameterSource().addValue("userId", userId)
					.addValue("cardIds", chunk).addValue("deletedAt", timestamp).addValue("updatedAt", timestamp);
			updated += namedJdbc.update("""
					UPDATE ziper.product_cards pc
					SET is_deleted = TRUE, deleted_at = :deletedAt, updated_at = :updatedAt
					WHERE pc.user_id = :userId
					  AND pc.id IN (:cardIds)
					  AND pc.is_deleted = FALSE
					  AND NOT EXISTS (
					      SELECT 1
					      FROM ziper.product_board_card pbc
					      JOIN ziper.product_boards pb ON pb.id = pbc.board_id
					      WHERE pbc.card_id = pc.id
					        AND pbc.is_deleted = FALSE
					        AND pb.is_deleted = FALSE
					  )
					""", params);
		}
		return updated;
	}
}
