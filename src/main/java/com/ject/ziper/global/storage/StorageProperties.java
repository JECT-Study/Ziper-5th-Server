package com.ject.ziper.global.storage;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("app.storage")
public record StorageProperties(String provider, String supabaseUrl, String supabaseServiceRoleKey, String bucket,
		Long signedUrlExpirationSeconds, Long maxFileSizeBytes, Boolean mirroringEnabled,
		List<String> disabledMirrorHosts, Boolean workerEnabled, Long mirrorPollIntervalMs, Integer mirrorBatchSize,
		Integer mirrorConnectTimeoutMs, Integer mirrorReadTimeoutMs, Integer mirrorHostMaxConcurrency,
		Long mirrorHostMinIntervalMs, Integer mirrorCircuitFailureThreshold, Long mirrorCircuitOpenSeconds,
		Long mirrorDedupTtlSeconds, Long mirrorDedupMaxBytes, Integer mirrorMaxAttempts,
		Long mirrorRetryBaseDelaySeconds, Long mirrorRetryMaxDelaySeconds, Long mirrorLockTimeoutSeconds,
		Integer supabaseConnectTimeoutMs, Integer supabaseReadTimeoutMs) {

	public static final long DEFAULT_MAX_FILE_SIZE_BYTES = 5_242_880L;
	public static final long DEFAULT_DEDUP_MAX_BYTES = 16L * 1024 * 1024;

	@ConstructorBinding
	public StorageProperties {
		provider = provider == null || provider.isBlank() ? "supabase" : provider.trim().toLowerCase(Locale.ROOT);
		if (!"supabase".equals(provider) && !"memory".equals(provider)) {
			throw new IllegalArgumentException("app.storage.provider must be 'supabase' or 'memory'");
		}
		supabaseUrl = normalizeBaseUrl(supabaseUrl);
		supabaseServiceRoleKey = supabaseServiceRoleKey == null ? "" : supabaseServiceRoleKey.trim();
		bucket = bucket == null || bucket.isBlank() ? "product-images" : bucket.trim();
		signedUrlExpirationSeconds = signedUrlExpirationSeconds == null ? 900L : signedUrlExpirationSeconds;
		if (signedUrlExpirationSeconds <= 0) {
			throw new IllegalArgumentException("app.storage.signed-url-expiration-seconds must be positive");
		}
		maxFileSizeBytes = maxFileSizeBytes == null ? DEFAULT_MAX_FILE_SIZE_BYTES : maxFileSizeBytes;
		if (maxFileSizeBytes <= 0) {
			throw new IllegalArgumentException("app.storage.max-file-size-bytes must be positive");
		}
		mirroringEnabled = mirroringEnabled == null ? Boolean.TRUE : mirroringEnabled;
		disabledMirrorHosts = disabledMirrorHosts == null
				? List.of()
				: disabledMirrorHosts.stream().map(host -> host.trim().toLowerCase(Locale.ROOT))
						.filter(host -> !host.isEmpty()).toList();
		workerEnabled = workerEnabled == null ? Boolean.TRUE : workerEnabled;
		mirrorPollIntervalMs = mirrorPollIntervalMs == null ? 5000L : mirrorPollIntervalMs;
		if (mirrorPollIntervalMs <= 0) {
			throw new IllegalArgumentException("app.storage.mirror-poll-interval-ms must be positive");
		}
		mirrorBatchSize = mirrorBatchSize == null ? 5 : mirrorBatchSize;
		if (mirrorBatchSize <= 0) {
			throw new IllegalArgumentException("app.storage.mirror-batch-size must be positive");
		}
		mirrorConnectTimeoutMs = mirrorConnectTimeoutMs == null ? 5000 : mirrorConnectTimeoutMs;
		if (mirrorConnectTimeoutMs <= 0) {
			throw new IllegalArgumentException("app.storage.mirror-connect-timeout-ms must be positive");
		}
		mirrorReadTimeoutMs = mirrorReadTimeoutMs == null ? 10000 : mirrorReadTimeoutMs;
		if (mirrorReadTimeoutMs <= 0) {
			throw new IllegalArgumentException("app.storage.mirror-read-timeout-ms must be positive");
		}
		mirrorHostMaxConcurrency = mirrorHostMaxConcurrency == null ? 2 : mirrorHostMaxConcurrency;
		if (mirrorHostMaxConcurrency <= 0) {
			throw new IllegalArgumentException("app.storage.mirror-host-max-concurrency must be positive");
		}
		mirrorHostMinIntervalMs = mirrorHostMinIntervalMs == null ? 200L : mirrorHostMinIntervalMs;
		if (mirrorHostMinIntervalMs < 0) {
			throw new IllegalArgumentException("app.storage.mirror-host-min-interval-ms must be non-negative");
		}
		mirrorCircuitFailureThreshold = mirrorCircuitFailureThreshold == null ? 3 : mirrorCircuitFailureThreshold;
		if (mirrorCircuitFailureThreshold <= 0) {
			throw new IllegalArgumentException("app.storage.mirror-circuit-failure-threshold must be positive");
		}
		mirrorCircuitOpenSeconds = mirrorCircuitOpenSeconds == null ? 300L : mirrorCircuitOpenSeconds;
		if (mirrorCircuitOpenSeconds <= 0) {
			throw new IllegalArgumentException("app.storage.mirror-circuit-open-seconds must be positive");
		}
		mirrorDedupTtlSeconds = mirrorDedupTtlSeconds == null ? 30L : mirrorDedupTtlSeconds;
		if (mirrorDedupTtlSeconds < 0) {
			throw new IllegalArgumentException("app.storage.mirror-dedup-ttl-seconds must be non-negative");
		}
		mirrorDedupMaxBytes = mirrorDedupMaxBytes == null ? DEFAULT_DEDUP_MAX_BYTES : mirrorDedupMaxBytes;
		if (mirrorDedupMaxBytes < 0) {
			throw new IllegalArgumentException("app.storage.mirror-dedup-max-bytes must be non-negative");
		}
		mirrorMaxAttempts = mirrorMaxAttempts == null ? 5 : mirrorMaxAttempts;
		if (mirrorMaxAttempts <= 0) {
			throw new IllegalArgumentException("app.storage.mirror-max-attempts must be positive");
		}
		mirrorRetryBaseDelaySeconds = mirrorRetryBaseDelaySeconds == null ? 60L : mirrorRetryBaseDelaySeconds;
		if (mirrorRetryBaseDelaySeconds <= 0) {
			throw new IllegalArgumentException("app.storage.mirror-retry-base-delay-seconds must be positive");
		}
		mirrorRetryMaxDelaySeconds = mirrorRetryMaxDelaySeconds == null ? 3600L : mirrorRetryMaxDelaySeconds;
		if (mirrorRetryMaxDelaySeconds < mirrorRetryBaseDelaySeconds) {
			throw new IllegalArgumentException(
					"app.storage.mirror-retry-max-delay-seconds must not be less than the base delay");
		}
		mirrorLockTimeoutSeconds = mirrorLockTimeoutSeconds == null ? 600L : mirrorLockTimeoutSeconds;
		if (mirrorLockTimeoutSeconds <= 0) {
			throw new IllegalArgumentException("app.storage.mirror-lock-timeout-seconds must be positive");
		}
		supabaseConnectTimeoutMs = supabaseConnectTimeoutMs == null ? 3000 : supabaseConnectTimeoutMs;
		if (supabaseConnectTimeoutMs <= 0) {
			throw new IllegalArgumentException("app.storage.supabase-connect-timeout-ms must be positive");
		}
		supabaseReadTimeoutMs = supabaseReadTimeoutMs == null ? 15000 : supabaseReadTimeoutMs;
		if (supabaseReadTimeoutMs <= 0) {
			throw new IllegalArgumentException("app.storage.supabase-read-timeout-ms must be positive");
		}
	}

	public static StorageProperties defaultsForMemory() {
		return new StorageProperties("memory", "", "", "product-images", 900L, DEFAULT_MAX_FILE_SIZE_BYTES, true,
				List.of(), false, 5000L, 5, 5000, 10000, 2, 0L, 3, 300L, 30L, DEFAULT_DEDUP_MAX_BYTES, 5, 60L, 3600L,
				600L, 3000, 15000);
	}

	/**
	 * Test/override helper: copies this configuration with a different dedup cache
	 * budget.
	 */
	public StorageProperties withDedup(long ttlSeconds, long maxBytes) {
		return new StorageProperties(provider, supabaseUrl, supabaseServiceRoleKey, bucket, signedUrlExpirationSeconds,
				maxFileSizeBytes, mirroringEnabled, disabledMirrorHosts, workerEnabled, mirrorPollIntervalMs,
				mirrorBatchSize, mirrorConnectTimeoutMs, mirrorReadTimeoutMs, mirrorHostMaxConcurrency,
				mirrorHostMinIntervalMs, mirrorCircuitFailureThreshold, mirrorCircuitOpenSeconds, ttlSeconds, maxBytes,
				mirrorMaxAttempts, mirrorRetryBaseDelaySeconds, mirrorRetryMaxDelaySeconds, mirrorLockTimeoutSeconds,
				supabaseConnectTimeoutMs, supabaseReadTimeoutMs);
	}

	public boolean isHostDisabled(String host) {
		if (host == null || host.isBlank()) {
			return false;
		}
		String normalized = host.trim().toLowerCase(Locale.ROOT);
		return disabledMirrorHosts.contains(normalized);
	}

	@Override
	public String toString() {
		return "StorageProperties[provider=" + provider + ", supabaseUrl=" + supabaseUrl
				+ ", supabaseServiceRoleKey=[REDACTED], bucket=" + bucket + ", signedUrlExpirationSeconds="
				+ signedUrlExpirationSeconds + ", maxFileSizeBytes=" + maxFileSizeBytes + ", mirroringEnabled="
				+ mirroringEnabled + ", disabledMirrorHosts=" + disabledMirrorHosts + ", workerEnabled=" + workerEnabled
				+ ", mirrorPollIntervalMs=" + mirrorPollIntervalMs + ", mirrorBatchSize=" + mirrorBatchSize
				+ ", mirrorConnectTimeoutMs=" + mirrorConnectTimeoutMs + ", mirrorReadTimeoutMs=" + mirrorReadTimeoutMs
				+ ", mirrorHostMaxConcurrency=" + mirrorHostMaxConcurrency + ", mirrorHostMinIntervalMs="
				+ mirrorHostMinIntervalMs + ", mirrorCircuitFailureThreshold=" + mirrorCircuitFailureThreshold
				+ ", mirrorCircuitOpenSeconds=" + mirrorCircuitOpenSeconds + ", mirrorDedupTtlSeconds="
				+ mirrorDedupTtlSeconds + ", mirrorDedupMaxBytes=" + mirrorDedupMaxBytes + ", mirrorMaxAttempts="
				+ mirrorMaxAttempts + ", mirrorRetryBaseDelaySeconds=" + mirrorRetryBaseDelaySeconds
				+ ", mirrorRetryMaxDelaySeconds=" + mirrorRetryMaxDelaySeconds + ", mirrorLockTimeoutSeconds="
				+ mirrorLockTimeoutSeconds + ", supabaseConnectTimeoutMs=" + supabaseConnectTimeoutMs
				+ ", supabaseReadTimeoutMs=" + supabaseReadTimeoutMs + "]";
	}

	private static String normalizeBaseUrl(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		String trimmed = raw.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}
}
