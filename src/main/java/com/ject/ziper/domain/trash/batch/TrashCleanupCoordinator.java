package com.ject.ziper.domain.trash.batch;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.trash.batch.TrashCleanupChunkService.ChunkResult;
import com.ject.ziper.global.persistence.AdvisoryLockRepository;

@Service
public class TrashCleanupCoordinator {
	private static final String LOCK_KEY = "trash-cleanup:expired-products";

	private final AdvisoryLockRepository locks;
	private final TrashCleanupChunkService chunks;
	private final TrashCleanupProperties properties;

	public TrashCleanupCoordinator(AdvisoryLockRepository locks, TrashCleanupChunkService chunks,
			TrashCleanupProperties properties) {
		this.locks = locks;
		this.chunks = chunks;
		this.properties = properties;
	}

	@Transactional
	public CleanupResult runOnce() {
		if (!locks.tryLock(LOCK_KEY)) {
			return new CleanupResult(true, 0, 0);
		}
		int deleted = 0;
		int chunkCount = 0;
		ChunkResult chunk;
		do {
			chunk = chunks.deleteNextChunk(properties.chunkSize());
			deleted += chunk.deleted();
			if (chunk.selected() > 0) {
				chunkCount++;
			}
		} while (chunk.selected() == properties.chunkSize());
		return new CleanupResult(false, deleted, chunkCount);
	}

	public record CleanupResult(boolean skipped, int deleted, int chunks) {
	}
}
