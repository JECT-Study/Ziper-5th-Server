package com.ject.ziper.domain.product.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProductUrlLookupRepository {
	private final JdbcTemplate jdbc;

	public ProductUrlLookupRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<Long> findCardIdByUserIdAndProductUrl(long userId, String productUrl) {
		List<Long> ids = jdbc.query("SELECT id FROM ziper.product_cards WHERE user_id = ? AND product_url = ? LIMIT 1",
				(result, row) -> result.getLong("id"), userId, productUrl);
		return ids.isEmpty() ? Optional.empty() : Optional.of(ids.getFirst());
	}
}
