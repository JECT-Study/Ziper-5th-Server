package com.ject.ziper.domain.trash.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ject.ziper.domain.trash.batch.TrashCleanupCoordinator.CleanupResult;

import io.sentry.Sentry;

@Component
@ConditionalOnProperty(prefix = "app.trash.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TrashCleanupScheduler {
	private static final Logger log = LoggerFactory.getLogger(TrashCleanupScheduler.class);

	private final TrashCleanupCoordinator coordinator;

	public TrashCleanupScheduler(TrashCleanupCoordinator coordinator) {
		this.coordinator = coordinator;
	}

	@Scheduled(cron = "${app.trash.cleanup.cron:0 0 3 * * *}", zone = "${app.trash.cleanup.zone:Asia/Seoul}")
	public void cleanupExpiredProducts() {
		long started = System.nanoTime();
		try {
			CleanupResult result = coordinator.runOnce();
			log.atInfo().addKeyValue("batch", "trash-cleanup").addKeyValue("skipped", result.skipped())
					.addKeyValue("deletedCount", result.deleted()).addKeyValue("chunkCount", result.chunks())
					.addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000).log("Trash cleanup finished");
		} catch (RuntimeException exception) {
			log.atError().addKeyValue("batch", "trash-cleanup")
					.addKeyValue("durationMs", (System.nanoTime() - started) / 1_000_000).setCause(exception)
					.log("Trash cleanup failed");
			Sentry.captureException(exception);
			throw exception;
		}
	}
}
