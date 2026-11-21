package com.ject.ziper.domain.product.link.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ject.ziper.domain.product.link.entity.ProductLink;

public interface ProductLinkRepository extends JpaRepository<ProductLink, Long> {
	Optional<ProductLink> findByCanonicalUrl(String canonicalUrl);
}
