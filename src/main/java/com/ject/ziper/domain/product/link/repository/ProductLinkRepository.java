package com.ject.ziper.domain.product.link.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ject.ziper.domain.product.link.entity.ProductLink;

public interface ProductLinkRepository extends JpaRepository<ProductLink, Long> {
	Optional<ProductLink> findByCanonicalUrl(String canonicalUrl);

	/** 엔티티를 영속성 컨텍스트에 올리지 않고 ID만 조회한다. 링크 잠금을 잡은 뒤 최신 상태를 읽기 위해 사용한다. */
	@Query("SELECT l.id FROM ProductLink l WHERE l.canonicalUrl = :canonicalUrl")
	Optional<Long> findIdByCanonicalUrl(@Param("canonicalUrl") String canonicalUrl);
}
