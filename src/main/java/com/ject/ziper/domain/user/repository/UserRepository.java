package com.ject.ziper.domain.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ject.ziper.domain.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
	boolean existsByNickname(String nickname);
}
