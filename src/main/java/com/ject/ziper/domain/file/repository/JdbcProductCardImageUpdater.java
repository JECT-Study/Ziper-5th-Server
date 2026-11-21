package com.ject.ziper.domain.file.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProductCardImageUpdater implements ProductCardImageUpdater {
	private final JdbcTemplate jdbcTemplate;

	public JdbcProductCardImageUpdater(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public boolean markMirrorReady(Long cardId, String expectedImageUrl, Long fileId) {
		return jdbcTemplate.update("""
				UPDATE ziper.product_cards
				SET image_file_id = ?, image_status = 'READY', updated_at = CURRENT_TIMESTAMP
				WHERE id = ? AND is_deleted = FALSE AND image_url = ?
				""", fileId, cardId, expectedImageUrl) > 0;
	}

	@Override
	public boolean markMirrorFailed(Long cardId, String expectedImageUrl) {
		// The original image_url is kept so the client can still render it as a
		// fallback.
		return jdbcTemplate.update("""
				UPDATE ziper.product_cards
				SET image_status = 'FAILED', updated_at = CURRENT_TIMESTAMP
				WHERE id = ? AND is_deleted = FALSE AND image_url = ?
				""", cardId, expectedImageUrl) > 0;
	}
}
