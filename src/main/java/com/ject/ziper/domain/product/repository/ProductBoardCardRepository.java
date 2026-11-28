package com.ject.ziper.domain.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ject.ziper.domain.product.entity.ProductBoardCard;

public interface ProductBoardCardRepository extends JpaRepository<ProductBoardCard, Long> {
	List<ProductBoardCard> findAllByCardIdOrderByIdAsc(Long cardId);

	List<ProductBoardCard> findAllByCardIdAndDeletedFalseOrderByIdAsc(Long cardId);

	boolean existsByBoardIdAndCardIdAndDeletedFalse(Long boardId, Long cardId);
}
