package com.ject.ziper.domain.product.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ject.ziper.domain.product.entity.UserCardEvent;

public interface UserCardEventRepository extends JpaRepository<UserCardEvent, Long> {
	List<UserCardEvent> findAllByCardIdOrderByViewedAtDescIdDesc(Long cardId);

	List<UserCardEvent> findAllByUserIdOrderByViewedAtDescIdDesc(Long userId);
}
