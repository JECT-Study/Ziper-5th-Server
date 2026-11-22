package com.ject.ziper.domain.board.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ject.ziper.domain.board.dto.BoardProductCursor;
import com.ject.ziper.domain.board.dto.BoardProductSort;
import com.ject.ziper.domain.product.entity.FallbackIcon;
import com.ject.ziper.domain.product.entity.ProductImageStatus;
import com.ject.ziper.domain.product.link.entity.ProductLinkCheckSource;
import com.ject.ziper.domain.product.link.entity.ProductLinkStatus;
import com.ject.ziper.domain.tag.dto.TagResponse;

@Repository
public class BoardQueryRepository {
	private final NamedParameterJdbcTemplate namedJdbc;

	public BoardQueryRepository(JdbcTemplate jdbc) {
		this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
	}

	public List<BoardSummaryRow> findActiveBoardsWithPlannedCounts(Long userId) {
		MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
		return namedJdbc.query("""
				SELECT pb.id AS board_id,
				       pb.name,
				       pb.modifiable,
				       pb.created_at,
				       COALESCE(counts.product_count, 0) AS product_count
				FROM ziper.product_boards pb
				LEFT JOIN (
				    SELECT pbc.board_id, COUNT(*) AS product_count
				    FROM ziper.product_board_card pbc
				    JOIN ziper.product_cards pc ON pc.id = pbc.card_id
				    WHERE pc.user_id = :userId
				      AND pc.is_deleted = FALSE
				      AND pc.status = 'PLANNED'
				    GROUP BY pbc.board_id
				) counts ON counts.board_id = pb.id
				WHERE pb.user_id = :userId
				  AND pb.is_deleted = FALSE
				ORDER BY pb.created_at DESC, pb.id DESC
				""", params, (rs, rowNum) -> {
			OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
			return new BoardSummaryRow(rs.getLong("board_id"), rs.getString("name"), rs.getBoolean("modifiable"),
					rs.getInt("product_count"), createdAt == null ? null : createdAt.toInstant());
		});
	}

	public Map<Long, List<PreviewImageRow>> findBoardPreviewImages(Long userId) {
		MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
		Map<Long, List<PreviewImageRow>> previewsByBoardId = new LinkedHashMap<>();
		namedJdbc.query("""
				SELECT ranked.board_id,
				       ranked.card_id,
				       ranked.image_file_id,
				       ranked.image_url,
				       ranked.fallback_icon
				FROM (
				    SELECT pbc.board_id,
				           pc.id AS card_id,
				           pc.image_file_id,
				           pc.image_url,
				           pc.fallback_icon,
				           ROW_NUMBER() OVER (
				               PARTITION BY pbc.board_id
				               ORDER BY pbc.created_at DESC, pbc.id DESC
				           ) AS rn
				    FROM ziper.product_board_card pbc
				    JOIN ziper.product_boards pb ON pb.id = pbc.board_id
				    JOIN ziper.product_cards pc ON pc.id = pbc.card_id
				    WHERE pb.user_id = :userId
				      AND pb.is_deleted = FALSE
				      AND pc.user_id = :userId
				      AND pc.is_deleted = FALSE
				      AND pc.status = 'PLANNED'
				) ranked
				WHERE ranked.rn <= 3
				ORDER BY ranked.board_id ASC, ranked.rn ASC
				""", params, rs -> {
			Long boardId = rs.getLong("board_id");
			PreviewImageRow row = new PreviewImageRow(boardId, rs.getLong("card_id"),
					rs.getObject("image_file_id", Long.class), rs.getString("image_url"),
					FallbackIcon.fromValue(rs.getString("fallback_icon")).orElse(null));
			previewsByBoardId.computeIfAbsent(boardId, key -> new ArrayList<>()).add(row);
		});
		return previewsByBoardId;
	}

	public List<PurchasedPreviewRow> findPurchasedSummaryPreviewRows(Long userId) {
		MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
		return namedJdbc.query("""
				SELECT pc.id AS card_id,
				       pc.image_file_id,
				       pc.image_url,
				       pc.fallback_icon,
				       COUNT(*) OVER () AS total_count
				FROM ziper.product_cards pc
				WHERE pc.user_id = :userId
				  AND pc.is_deleted = FALSE
				  AND pc.status = 'PURCHASED'
				ORDER BY pc.updated_at DESC, pc.id DESC
				LIMIT 3
				""", params,
				(rs, rowNum) -> new PurchasedPreviewRow(rs.getLong("card_id"),
						rs.getObject("image_file_id", Long.class), rs.getString("image_url"),
						FallbackIcon.fromValue(rs.getString("fallback_icon")).orElse(null), rs.getInt("total_count")));
	}

	public Optional<ActiveBoardRow> findActiveBoard(Long userId, Long boardId) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("userId", userId).addValue("boardId",
				boardId);
		List<ActiveBoardRow> rows = namedJdbc.query("""
				SELECT pb.id AS board_id,
				       pb.name,
				       pb.modifiable
				FROM ziper.product_boards pb
				WHERE pb.id = :boardId
				  AND pb.user_id = :userId
				  AND pb.is_deleted = FALSE
				""", params, (rs, rowNum) -> new ActiveBoardRow(rs.getLong("board_id"), rs.getString("name"),
				rs.getBoolean("modifiable")));
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
	}

	public int countBoardProducts(Long userId, Long boardId, List<Long> tagIds) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("userId", userId).addValue("boardId",
				boardId);
		StringBuilder sql = new StringBuilder("""
				SELECT COUNT(*)
				FROM ziper.product_board_card pbc
				JOIN ziper.product_cards pc ON pc.id = pbc.card_id
				WHERE pbc.board_id = :boardId
				  AND pc.user_id = :userId
				  AND pc.is_deleted = FALSE
				  AND pc.status = 'PLANNED'
				""");
		if (!tagIds.isEmpty()) {
			params.addValue("tagIds", tagIds);
			sql.append("""
					  AND EXISTS (
					      SELECT 1
					      FROM ziper.product_card_tags pct
					      WHERE pct.card_id = pc.id
					        AND pct.tag_id IN (:tagIds)
					  )
					""");
		}
		Integer count = namedJdbc.queryForObject(sql.toString(), params, Integer.class);
		return count == null ? 0 : count;
	}

	public List<BoardProductRow> findBoardProducts(Long userId, Long boardId, List<Long> tagIds, BoardProductSort sort,
			BoardProductCursor cursor, int limit) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("userId", userId)
				.addValue("boardId", boardId).addValue("limit", limit);
		StringBuilder sql = new StringBuilder("""
				SELECT pc.id AS card_id,
				       pbc.id AS board_card_id,
				       pc.shop,
				       pc.product_title,
				       pc.price,
				       pc.memo,
				       pc.image_url,
				       pc.image_file_id,
				       pc.fallback_icon,
				       pc.image_status,
				       pc.link_status,
				       pc.link_checked_at,
				       pc.link_check_source,
				       pbc.created_at AS added_at
				FROM ziper.product_board_card pbc
				JOIN ziper.product_cards pc ON pc.id = pbc.card_id
				WHERE pbc.board_id = :boardId
				  AND pc.user_id = :userId
				  AND pc.is_deleted = FALSE
				  AND pc.status = 'PLANNED'
				""");
		if (!tagIds.isEmpty()) {
			params.addValue("tagIds", tagIds);
			sql.append("""
					  AND EXISTS (
					      SELECT 1
					      FROM ziper.product_card_tags pct
					      WHERE pct.card_id = pc.id
					        AND pct.tag_id IN (:tagIds)
					  )
					""");
		}
		if (cursor != null) {
			params.addValue("addedAt", cursor.addedAt().atOffset(ZoneOffset.UTC))
					.addValue("boardCardId", cursor.boardCardId()).addValue("price", cursor.price());
			sql.append("  AND ").append(seekClause(sort)).append('\n');
		}
		sql.append("ORDER BY ").append(orderByClause(sort)).append("\nLIMIT :limit");
		return namedJdbc.query(sql.toString(), params, BoardQueryRepository::mapBoardProductRow);
	}

	public Map<Long, List<TagResponse>> findTagsByCardIds(List<Long> cardIds) {
		if (cardIds.isEmpty()) {
			return Map.of();
		}
		MapSqlParameterSource params = new MapSqlParameterSource("cardIds", cardIds);
		Map<Long, List<TagResponse>> tagsByCardId = new LinkedHashMap<>();
		namedJdbc.query("""
				SELECT pct.card_id,
				       t.id AS tag_id,
				       t.title,
				       t.is_default
				FROM ziper.product_card_tags pct
				JOIN ziper.tags t ON t.id = pct.tag_id
				WHERE pct.card_id IN (:cardIds)
				ORDER BY pct.card_id ASC, t.is_default DESC, t.id ASC
				""", params, rs -> {
			Long cardId = rs.getLong("card_id");
			TagResponse tag = new TagResponse(rs.getLong("tag_id"), rs.getString("title"), rs.getBoolean("is_default"));
			tagsByCardId.computeIfAbsent(cardId, key -> new ArrayList<>()).add(tag);
		});
		return tagsByCardId;
	}

	private static String seekClause(BoardProductSort sort) {
		String older = "(pbc.created_at, pbc.id) < (:addedAt, :boardCardId)";
		return switch (sort) {
			case RECENT -> older;
			case OLDEST -> "(pbc.created_at, pbc.id) > (:addedAt, :boardCardId)";
			case PRICE_ASC -> "(pc.price > :price OR (pc.price = :price AND " + older + "))";
			case PRICE_DESC -> "(pc.price < :price OR (pc.price = :price AND " + older + "))";
		};
	}

	private static String orderByClause(BoardProductSort sort) {
		return switch (sort) {
			case RECENT -> "pbc.created_at DESC, pbc.id DESC";
			case OLDEST -> "pbc.created_at ASC, pbc.id ASC";
			case PRICE_ASC -> "pc.price ASC, pbc.created_at DESC, pbc.id DESC";
			case PRICE_DESC -> "pc.price DESC, pbc.created_at DESC, pbc.id DESC";
		};
	}

	private static BoardProductRow mapBoardProductRow(ResultSet rs, int rowNum) throws SQLException {
		OffsetDateTime linkCheckedAt = rs.getObject("link_checked_at", OffsetDateTime.class);
		OffsetDateTime addedAt = rs.getObject("added_at", OffsetDateTime.class);
		return new BoardProductRow(rs.getLong("card_id"), rs.getLong("board_card_id"), rs.getString("shop"),
				rs.getString("product_title"), rs.getLong("price"), rs.getString("memo"), rs.getString("image_url"),
				rs.getObject("image_file_id", Long.class),
				FallbackIcon.fromValue(rs.getString("fallback_icon")).orElse(null),
				ProductImageStatus.fromValue(rs.getString("image_status")).orElse(ProductImageStatus.NONE),
				ProductLinkStatus.fromValue(rs.getString("link_status")).orElse(ProductLinkStatus.UNKNOWN),
				linkCheckedAt == null ? null : linkCheckedAt.toInstant(),
				ProductLinkCheckSource.fromValue(rs.getString("link_check_source")).orElse(null),
				addedAt == null ? null : addedAt.toInstant());
	}

	public record BoardSummaryRow(Long boardId, String name, boolean modifiable, int productCount, Instant createdAt) {
	}

	public record PreviewImageRow(Long boardId, Long cardId, Long imageFileId, String imageUrl,
			FallbackIcon fallbackIcon) {
	}

	public record PurchasedPreviewRow(Long cardId, Long imageFileId, String imageUrl, FallbackIcon fallbackIcon,
			int totalCount) {
	}

	public record ActiveBoardRow(Long boardId, String name, boolean modifiable) {
	}

	public record BoardProductRow(Long cardId, long boardCardId, String shop, String productTitle, Long price,
			String memo, String imageUrl, Long imageFileId, FallbackIcon fallbackIcon, ProductImageStatus imageStatus,
			ProductLinkStatus linkStatus, Instant linkCheckedAt, ProductLinkCheckSource linkCheckSource,
			Instant addedAt) {
	}
}
