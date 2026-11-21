package com.ject.ziper.domain.tag.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ject.ziper.domain.tag.entity.Tag;

public interface TagRepository extends JpaRepository<Tag, Long> {
	@Query("SELECT t FROM Tag t WHERE t.userId IS NULL OR t.userId = :userId ORDER BY t.defaultTag DESC, t.id ASC")
	List<Tag> findAccessibleByUserId(@Param("userId") Long userId);

	@Query("SELECT CASE WHEN COUNT(t) > 0 THEN TRUE ELSE FALSE END FROM Tag t "
			+ "WHERE (t.userId IS NULL OR t.userId = :userId) AND t.title = :title")
	boolean existsAccessibleTitle(@Param("userId") Long userId, @Param("title") String title);

	@Query("SELECT t FROM Tag t WHERE (t.userId IS NULL OR t.userId = :userId) AND t.title IN :titles")
	List<Tag> findAccessibleByUserIdAndTitleIn(@Param("userId") Long userId,
			@Param("titles") Collection<String> titles);
}
