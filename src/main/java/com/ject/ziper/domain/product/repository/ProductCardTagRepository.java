package com.ject.ziper.domain.product.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ject.ziper.domain.product.entity.ProductCardTag;

public interface ProductCardTagRepository extends JpaRepository<ProductCardTag, Long> {
	List<ProductCardTag> findAllByCardIdOrderByIdAsc(Long cardId);

	List<ProductCardTag> findAllByCardIdIn(Collection<Long> cardIds);

	void deleteAllByCardId(Long cardId);
}
