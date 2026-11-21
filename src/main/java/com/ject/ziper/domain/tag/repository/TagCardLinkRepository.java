package com.ject.ziper.domain.tag.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TagCardLinkRepository {
	private final JdbcTemplate jdbc;

	public TagCardLinkRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public boolean existsCardOwnedByUser(Long cardId, Long userId) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM ziper.product_cards WHERE id = ? AND user_id = ?",
				Integer.class, cardId, userId);
		return count != null && count > 0;
	}

	public int countOtherLinkedCards(Long tagId, Long excludeCardId) {
		Integer count = jdbc.queryForObject(
				"SELECT COUNT(*) FROM ziper.product_card_tags WHERE tag_id = ? AND card_id <> ?", Integer.class, tagId,
				excludeCardId);
		return count == null ? 0 : count;
	}

	public void unlinkCardTag(Long cardId, Long tagId) {
		jdbc.update("DELETE FROM ziper.product_card_tags WHERE card_id = ? AND tag_id = ?", cardId, tagId);
	}
}
