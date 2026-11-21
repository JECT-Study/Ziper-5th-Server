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
	public void markMirrorReady(Long cardId, Long fileId) {
		jdbcTemplate.update(
				"UPDATE ziper.product_cards SET image_file_id = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?", fileId,
				cardId);
	}

	@Override
	public void markMirrorFailed(Long cardId) {
		// Original image_url is intentionally preserved for fallback rendering;
		// Wave 2 (JEC-54) may extend this updater to also record image_status =
		// 'FAILED'.
	}
}
