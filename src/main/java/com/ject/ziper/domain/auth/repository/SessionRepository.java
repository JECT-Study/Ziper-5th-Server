package com.ject.ziper.domain.auth.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ject.ziper.domain.auth.entity.Session;

public interface SessionRepository extends JpaRepository<Session, Long> {
	Optional<Session> findByTokenHash(String tokenHash);

	long deleteByTokenHash(String tokenHash);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Session s where s.tokenHash = :hash")
	Optional<Session> lockByTokenHash(@Param("hash") String hash);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Session s where s.tokenHash = :hash or s.previousTokenHash = :hash")
	Optional<Session> lockByCurrentOrPreviousTokenHash(@Param("hash") String hash);
}
