package com.ject.ziper.domain.auth.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ject.ziper.domain.auth.entity.Session;

public interface SessionRepository extends JpaRepository<Session, Long> {
	Optional<Session> findByTokenHash(String tokenHash);

	long deleteByTokenHash(String tokenHash);
}
