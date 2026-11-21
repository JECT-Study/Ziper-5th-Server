package com.ject.ziper.domain.product.link.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ject.ziper.domain.product.link.entity.ProductLinkReport;
import com.ject.ziper.domain.product.link.entity.ProductLinkStatus;

public interface ProductLinkReportRepository extends JpaRepository<ProductLinkReport, Long> {
	boolean existsByUserIdAndLinkIdAndCreatedAtGreaterThanEqual(Long userId, Long linkId, Instant since);

	@Query("""
			SELECT COUNT(DISTINCT r.userId)
			FROM ProductLinkReport r
			WHERE r.linkId = :linkId
				AND r.status = :status
				AND r.createdAt >= :since
			""")
	long countDistinctReportersSince(@Param("linkId") Long linkId, @Param("status") ProductLinkStatus status,
			@Param("since") Instant since);
}
