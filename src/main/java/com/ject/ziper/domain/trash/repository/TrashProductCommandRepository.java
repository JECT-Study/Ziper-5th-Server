package com.ject.ziper.domain.trash.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TrashProductCommandRepository {
	private final JdbcTemplate jdbc;

	public TrashProductCommandRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public boolean existsOwnedDeletedCard(Long userId, Long productId) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS (
				    SELECT 1 FROM ziper.product_cards
				    WHERE id = ? AND user_id = ? AND is_deleted = TRUE
				)
				""", Boolean.class, productId, userId));
	}

	public int deleteOwnedDeletedCard(Long userId, Long productId) {
		return jdbc.update("""
				DELETE FROM ziper.product_cards
				WHERE id = ? AND user_id = ? AND is_deleted = TRUE
				""", productId, userId);
	}
}
