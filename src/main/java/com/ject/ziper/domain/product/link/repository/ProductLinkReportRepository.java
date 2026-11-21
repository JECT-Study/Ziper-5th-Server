package com.ject.ziper.domain.product.link.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ject.ziper.domain.product.link.entity.ProductLinkReport;
import com.ject.ziper.domain.product.link.entity.ProductLinkStatus;

public interface ProductLinkReportRepository extends JpaRepository<ProductLinkReport, Long> {
	boolean existsByUserIdAndLinkIdAndCreatedAtGreaterThanEqual(Long userId, Long linkId, Instant since);

	/**
	 * 사용자별 최신 보고(observedAt, id 기준)가 해당 상태이고 창 안에 기록된 보고들의 관측 시각을 반환한다.
	 */
	@Query("""
			SELECT r.observedAt
			FROM ProductLinkReport r
			WHERE r.linkId = :linkId
				AND r.status = :status
				AND r.createdAt >= :since
				AND NOT EXISTS (
					SELECT 1 FROM ProductLinkReport n
					WHERE n.linkId = r.linkId
						AND n.userId = r.userId
						AND (n.observedAt > r.observedAt OR (n.observedAt = r.observedAt AND n.id > r.id))
				)
			""")
	List<Instant> findLatestObservedAtPerReporter(@Param("linkId") Long linkId,
			@Param("status") ProductLinkStatus status, @Param("since") Instant since);
}
