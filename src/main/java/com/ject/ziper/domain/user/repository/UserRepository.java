package com.ject.ziper.domain.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ject.ziper.domain.user.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
	Optional<User> findByOauthConnectId(Long oauthConnectId);

	boolean existsByNickname(String nickname);

	boolean existsByEmail(String email);
}
