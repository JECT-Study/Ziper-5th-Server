package com.ject.ziper.domain.trash.repository;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrashCleanupRepository {
	private final JdbcTemplate jdbc;
	private final NamedParameterJdbcTemplate namedJdbc;

	public TrashCleanupRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
		this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
	}

	public List<Long> findExpiredCardIds(int limit) {
		return jdbc.queryForList("""
				SELECT id FROM ziper.product_cards
				WHERE is_deleted = TRUE
				  AND deleted_at <= CURRENT_TIMESTAMP - INTERVAL '30 days'
				ORDER BY deleted_at, id
				LIMIT ?
				""", Long.class, limit);
	}

	public int deleteExpiredCards(List<Long> ids) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("ids", ids);
		return namedJdbc.update("""
				DELETE FROM ziper.product_cards
				WHERE id IN (:ids)
				  AND is_deleted = TRUE
				  AND deleted_at <= CURRENT_TIMESTAMP - INTERVAL '30 days'
				""", params);
	}
}
