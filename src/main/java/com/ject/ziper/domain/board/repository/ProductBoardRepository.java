package com.ject.ziper.domain.board.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ject.ziper.domain.board.entity.ProductBoard;

public interface ProductBoardRepository extends JpaRepository<ProductBoard, Long> {
	List<ProductBoard> findAllByUserId(Long userId);
}
