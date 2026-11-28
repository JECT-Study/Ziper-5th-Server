package com.ject.ziper.domain.trash.batch;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("app.trash.cleanup")
public record TrashCleanupProperties(Boolean enabled, String cron, String zone, Integer chunkSize) {
	@ConstructorBinding
	public TrashCleanupProperties {
		enabled = enabled == null ? Boolean.TRUE : enabled;
		cron = cron == null || cron.isBlank() ? "0 0 3 * * *" : cron.trim();
		zone = zone == null || zone.isBlank() ? "Asia/Seoul" : zone.trim();
		ZoneId.of(zone);
		chunkSize = chunkSize == null ? 100 : chunkSize;
		if (chunkSize < 1 || chunkSize > 1000) {
			throw new IllegalArgumentException("app.trash.cleanup.chunk-size must be between 1 and 1000");
		}
	}
}
