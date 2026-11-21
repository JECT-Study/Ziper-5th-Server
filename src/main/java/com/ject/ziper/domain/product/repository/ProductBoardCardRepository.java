package com.ject.ziper.domain.product.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ject.ziper.domain.product.entity.ProductBoardCard;

public interface ProductBoardCardRepository extends JpaRepository<ProductBoardCard, Long> {
	List<ProductBoardCard> findAllByCardIdOrderByIdAsc(Long cardId);

	List<ProductBoardCard> findAllByCardIdIn(Collection<Long> cardIds);

	List<ProductBoardCard> findAllByBoardId(Long boardId);

	boolean existsByBoardIdAndCardId(Long boardId, Long cardId);

	void deleteAllByCardId(Long cardId);

	void deleteByBoardIdAndCardId(Long boardId, Long cardId);
}
