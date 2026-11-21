package com.ject.ziper.domain.file.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ject.ziper.domain.file.entity.ImageMirrorJob;

public interface ImageMirrorJobRepository extends JpaRepository<ImageMirrorJob, Long> {
	List<ImageMirrorJob> findByCardIdOrderByIdDesc(Long cardId);

	@Query(value = """
			SELECT *
			FROM ziper.image_mirror_jobs
			WHERE status = 'PENDING'
			  AND next_attempt_at <= :now
			ORDER BY priority DESC, next_attempt_at ASC, id ASC
			LIMIT :batchSize
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<ImageMirrorJob> findPendingForUpdateSkipLocked(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
