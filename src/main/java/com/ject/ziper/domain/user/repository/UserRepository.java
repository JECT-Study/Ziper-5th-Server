package com.ject.ziper.domain.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ject.ziper.domain.user.entity.User;
import com.ject.ziper.domain.user.entity.UserStatus;

public interface UserRepository extends JpaRepository<User, Long> {
	Optional<User> findByOauthConnectId(Long oauthConnectId);

	Optional<User> findByEmail(String email);

	boolean existsByNickname(String nickname);

	boolean existsByEmailIgnoreCase(String email);

	boolean existsByIdAndStatus(Long id, UserStatus status);
}
