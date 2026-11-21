package com.ject.ziper.domain.product.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ject.ziper.domain.product.entity.ProductCard;
import com.ject.ziper.domain.product.entity.ProductCardStatus;

public interface ProductCardRepository extends JpaRepository<ProductCard, Long> {
	boolean existsByUserIdAndProductUrl(Long userId, String productUrl);

	Optional<ProductCard> findByIdAndUserId(Long id, Long userId);

	Optional<ProductCard> findByIdAndUserIdAndDeletedFalse(Long id, Long userId);

	Optional<ProductCard> findByIdAndUserIdAndDeletedTrue(Long id, Long userId);

	Optional<ProductCard> findByUserIdAndProductUrl(Long userId, String productUrl);

	List<ProductCard> findAllByUserIdAndDeletedFalse(Long userId);

	List<ProductCard> findAllByUserIdAndStatusAndDeletedFalse(Long userId, ProductCardStatus status);

	List<ProductCard> findAllByUserIdAndDeletedTrueOrderByDeletedAtDescIdDesc(Long userId);
}
