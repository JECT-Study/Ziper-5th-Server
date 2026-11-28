package com.ject.ziper.domain.trash.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ject.ziper.domain.trash.dto.RestoreBoardItemResponse;

@Repository
public class TrashRestoreRepository {
	private final JdbcTemplate jdbc;
	private final NamedParameterJdbcTemplate namedJdbc;

	public TrashRestoreRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
		this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
	}

	public boolean existsRestorableCard(Long userId, Long cardId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS (
				    SELECT 1 FROM ziper.product_cards
				    WHERE id = ? AND user_id = ? AND is_deleted = TRUE
				      AND deleted_at > CURRENT_TIMESTAMP - INTERVAL '30 days'
				)
				""", Boolean.class, cardId, userId));
	}

	public List<RestoreBoardItemResponse> findActiveBoards(Long userId) {
		return jdbc.query("""
				SELECT id, name FROM ziper.product_boards
				WHERE user_id = ? AND is_deleted = FALSE
				ORDER BY created_at DESC, id DESC
				""", (rs, rowNum) -> new RestoreBoardItemResponse(rs.getLong("id"), rs.getString("name")), userId);
	}

	public int countActiveOwnedBoards(Long userId, List<Long> boardIds) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("userId", userId).addValue("boardIds",
				boardIds);
		Integer count = namedJdbc.queryForObject("""
				SELECT COUNT(*) FROM ziper.product_boards
				WHERE user_id = :userId AND is_deleted = FALSE AND id IN (:boardIds)
				""", params, Integer.class);
		return count == null ? 0 : count;
	}

	public int restoreCard(Long userId, Long cardId) {
		return jdbc.update("""
				UPDATE ziper.product_cards
				SET is_deleted = FALSE,
				    deleted_at = NULL,
				    status = 'PLANNED',
				    rediscovery_stage = 0,
				    last_interacted_at = CURRENT_TIMESTAMP,
				    next_rediscovery_at = CURRENT_TIMESTAMP + INTERVAL '14 days',
				    updated_at = CURRENT_TIMESTAMP
				WHERE id = ? AND user_id = ? AND is_deleted = TRUE
				  AND deleted_at > CURRENT_TIMESTAMP - INTERVAL '30 days'
				""", cardId, userId);
	}

	public void softDeleteActiveLinks(Long cardId) {
		jdbc.update("""
				UPDATE ziper.product_board_card
				SET is_deleted = TRUE, deleted_at = CURRENT_TIMESTAMP
				WHERE card_id = ? AND is_deleted = FALSE
				""", cardId);
	}

	public void reactivateOrCreateLink(Long boardId, Long cardId) {
		jdbc.update("""
				INSERT INTO ziper.product_board_card (board_id, card_id)
				VALUES (?, ?)
				ON CONFLICT (board_id, card_id) DO UPDATE
				SET is_deleted = FALSE,
				    deleted_at = NULL,
				    created_at = CURRENT_TIMESTAMP
				""", boardId, cardId);
	}
}
