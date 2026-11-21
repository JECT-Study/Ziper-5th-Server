package com.ject.ziper.domain.product.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ject.ziper.domain.product.entity.ProductCard;
import com.ject.ziper.domain.product.entity.ProductCardStatus;

import jakarta.persistence.LockModeType;

public interface ProductCardRepository extends JpaRepository<ProductCard, Long> {
	boolean existsByUserIdAndProductUrl(Long userId, String productUrl);

	Optional<ProductCard> findByIdAndUserId(Long id, Long userId);

	Optional<ProductCard> findByIdAndUserIdAndDeletedFalse(Long id, Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<ProductCard> findLockedByIdAndUserIdAndDeletedFalse(Long id, Long userId);

	/** 카드 엔티티를 영속성 컨텍스트에 올리지 않고 URL만 읽는다. 잠금 전에 엔티티를 올리면 잠금 뒤 재조회가 오래된 값을 돌려준다. */
	@Query("select c.productUrl from ProductCard c where c.id = :id and c.userId = :userId and c.deleted = false")
	Optional<String> findActiveProductUrl(@Param("id") Long id, @Param("userId") Long userId);

	Optional<ProductCard> findByIdAndUserIdAndDeletedTrue(Long id, Long userId);

	Optional<ProductCard> findByUserIdAndProductUrl(Long userId, String productUrl);

	List<ProductCard> findAllByUserIdAndDeletedFalse(Long userId);

	List<ProductCard> findAllByUserIdAndStatusAndDeletedFalse(Long userId, ProductCardStatus status);

	List<ProductCard> findAllByUserIdAndDeletedTrueOrderByDeletedAtDescIdDesc(Long userId);
}
