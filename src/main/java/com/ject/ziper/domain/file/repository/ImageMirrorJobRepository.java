package com.ject.ziper.domain.file.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ject.ziper.domain.file.entity.ImageMirrorJob;

public interface ImageMirrorJobRepository extends JpaRepository<ImageMirrorJob, Long> {
	List<ImageMirrorJob> findByCardIdOrderByIdDesc(Long cardId);

	/**
	 * Claimable jobs: due PENDING jobs plus PROCESSING jobs whose lease
	 * ({@code locked_at}) expired, i.e. the worker that claimed them died or lost
	 * its transaction.
	 */
	@Query(value = """
			SELECT *
			FROM ziper.image_mirror_jobs
			WHERE (status = 'PENDING' AND next_attempt_at <= :now)
			   OR (status = 'PROCESSING' AND locked_at <= :lockExpiredBefore)
			ORDER BY priority DESC, next_attempt_at ASC, id ASC
			LIMIT :batchSize
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<ImageMirrorJob> findClaimableForUpdateSkipLocked(@Param("now") Instant now,
			@Param("lockExpiredBefore") Instant lockExpiredBefore, @Param("batchSize") int batchSize);

	@Query(value = "SELECT * FROM ziper.image_mirror_jobs WHERE id = :id FOR UPDATE", nativeQuery = true)
	Optional<ImageMirrorJob> findByIdForUpdate(@Param("id") Long id);

	/**
	 * Extends the lease of a job this worker still owns; returns 0 when it was
	 * canceled or finished meanwhile.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE ziper.image_mirror_jobs
			SET locked_at = :now, updated_at = :now
			WHERE id = :id AND status = 'PROCESSING'
			""", nativeQuery = true)
	int refreshLease(@Param("id") Long id, @Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			UPDATE ziper.image_mirror_jobs
			SET status = 'CANCELED', last_error = 'CARD_CHANGED', locked_at = NULL, updated_at = :now
			WHERE card_id = :cardId AND status IN ('PENDING', 'PROCESSING')
			""", nativeQuery = true)
	int cancelActiveByCardId(@Param("cardId") Long cardId, @Param("now") Instant now);
}
