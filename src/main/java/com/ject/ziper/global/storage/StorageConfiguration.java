package com.ject.ziper.global.storage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {
	@Bean
	ObjectStorageClient objectStorageClient(StorageProperties properties, RestClient.Builder restClientBuilder) {
		if ("memory".equals(properties.provider())) {
			return new InMemoryObjectStorageClient();
		}
		if ("supabase".equals(properties.provider())) {
			return new SupabaseObjectStorageClient(properties, restClientBuilder);
		}
		throw new IllegalArgumentException("Unsupported storage provider: " + properties.provider());
	}

	@Bean(name = "imageMirrorExecutor", destroyMethod = "shutdown")
	ExecutorService imageMirrorExecutor() {
		AtomicInteger counter = new AtomicInteger(1);
		return Executors.newFixedThreadPool(2, runnable -> {
			Thread thread = new Thread(runnable, "image-mirror-worker-" + counter.getAndIncrement());
			thread.setDaemon(true);
			return thread;
		});
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "app.storage.worker-enabled", havingValue = "true", matchIfMissing = true)
	@EnableScheduling
	static class MirrorWorkerSchedulingConfiguration {
	}
}
