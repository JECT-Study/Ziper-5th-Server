package com.ject.ziper.domain.board.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ject.ziper.domain.board.entity.ProductBoard;

public interface ProductBoardRepository extends JpaRepository<ProductBoard, Long> {
	List<ProductBoard> findAllByUserId(Long userId);

	Optional<ProductBoard> findByIdAndUserIdAndDeletedFalse(Long id, Long userId);

	boolean existsByUserIdAndNameAndDeletedFalse(Long userId, String name);

	boolean existsByUserIdAndNameAndDeletedFalseAndIdNot(Long userId, String name, Long id);
}
