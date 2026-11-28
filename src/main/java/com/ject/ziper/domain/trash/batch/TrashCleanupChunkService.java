package com.ject.ziper.domain.trash.batch;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.file.service.ImageMirrorService;
import com.ject.ziper.domain.trash.repository.TrashCleanupRepository;

@Service
public class TrashCleanupChunkService {
	private final TrashCleanupRepository repository;
	private final ImageMirrorService imageMirrorService;

	public TrashCleanupChunkService(TrashCleanupRepository repository, ImageMirrorService imageMirrorService) {
		this.repository = repository;
		this.imageMirrorService = imageMirrorService;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public ChunkResult deleteNextChunk(int limit) {
		List<Long> ids = repository.findExpiredCardIds(limit);
		for (Long id : ids) {
			imageMirrorService.lockActiveJobsForCard(id);
		}
		int deleted = ids.isEmpty() ? 0 : repository.deleteExpiredCards(ids);
		return new ChunkResult(ids.size(), deleted);
	}

	public record ChunkResult(int selected, int deleted) {
	}
}
