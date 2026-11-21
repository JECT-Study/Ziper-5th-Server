package com.ject.ziper.domain.file.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ject.ziper.global.storage.StorageProperties;

@Component
public class ExternalImageFetcher {
	public static final int MAX_REDIRECT_HOPS = 3;
	private static final int MAX_DEDUP_CACHE_ENTRIES = 500;
	private static final int READ_BUFFER_SIZE = 8192;

	private static final Map<String, Set<String>> SUPPORTED_SHOP_IMAGE_HOSTS = Map.of("무신사", Set.of("image.msscdn.net"),
			"에이블리", Set.of("d3ha2047wt6x28.cloudfront.net"), "지그재그", Set.of("cf.product-image.s.zigzag.kr"));

	private final StorageProperties properties;
	private final HostMirrorCircuitBreaker circuitBreaker;
	private final DnsResolver dnsResolver;
	private final HttpTransport httpTransport;
	private final Clock clock;
	private final ConcurrentMap<String, CompletableFuture<FetchResult>> inFlightDownloads = new ConcurrentHashMap<>();
	private final DownloadCache recentDownloads = new DownloadCache();

	@Autowired
	public ExternalImageFetcher(StorageProperties properties, HostMirrorCircuitBreaker circuitBreaker) {
		this(properties, circuitBreaker, InetAddress::getAllByName,
				createDefaultTransport(properties, InetAddress::getAllByName, ExternalImageFetcher::isForbiddenAddress),
				Clock.systemUTC());
	}

	public ExternalImageFetcher(StorageProperties properties, HostMirrorCircuitBreaker circuitBreaker,
			DnsResolver dnsResolver, HttpTransport httpTransport, Clock clock) {
		this.properties = properties;
		this.circuitBreaker = circuitBreaker;
		this.dnsResolver = dnsResolver;
		this.httpTransport = httpTransport;
		this.clock = clock;
	}

	public static Optional<String> extractHttpsHost(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			return Optional.empty();
		}
		URI uri;
		try {
			uri = URI.create(rawUrl.trim());
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
		if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
				|| uri.getRawUserInfo() != null) {
			return Optional.empty();
		}
		return Optional.of(uri.getHost().trim().toLowerCase(Locale.ROOT));
	}

	public static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashed);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm not available", exception);
		}
	}

	public static boolean isExpiringSignedUrl(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			return false;
		}
		String lower = rawUrl.toLowerCase(Locale.ROOT);
		return lower.contains("oe=") || lower.contains("x-amz-expires=") || lower.contains("expires=");
	}

	public static boolean isAllowedHostForShop(String shop, String host) {
		if (host == null || host.isBlank()) {
			return false;
		}
		if (shop == null || shop.isBlank()) {
			return true;
		}
		Set<String> allowedHosts = SUPPORTED_SHOP_IMAGE_HOSTS.get(shop.trim());
		if (allowedHosts == null) {
			return true;
		}
		return allowedHosts.contains(host.trim().toLowerCase(Locale.ROOT));
	}

	public FetchResult fetch(String sourceUrl, String sourceUrlHash, String shop) {
		URI initialUri = parseValidatedUri(sourceUrl);
		if (initialUri == null) {
			return FetchResult.failure(null, "INVALID_OR_UNSUPPORTED_URL");
		}
		String initialValidationError = validateTargetUri(initialUri, shop);
		if (initialValidationError != null) {
			return FetchResult.failure(null, initialValidationError);
		}

		String dedupKey = sourceUrlHash == null || sourceUrlHash.isBlank()
				? sha256Hex(sourceUrl.trim())
				: sourceUrlHash;
		FetchResult cached = recentDownloads.get(dedupKey, clock.instant());
		if (cached != null) {
			return cached.copy();
		}

		CompletableFuture<FetchResult> newFuture = new CompletableFuture<>();
		CompletableFuture<FetchResult> existing = inFlightDownloads.putIfAbsent(dedupKey, newFuture);
		if (existing != null) {
			try {
				return existing.join().copy();
			} catch (RuntimeException exception) {
				return FetchResult.failure(null, "DEDUP_JOIN_FAILED");
			}
		}

		try {
			FetchResult result = downloadWithRedirects(initialUri, shop);
			if (result.success() && properties.mirrorDedupTtlSeconds() > 0) {
				recentDownloads.put(dedupKey, result, clock.instant(), properties.mirrorDedupTtlSeconds(),
						properties.mirrorDedupMaxBytes(), MAX_DEDUP_CACHE_ENTRIES);
			}
			newFuture.complete(result);
			return result.copy();
		} catch (RuntimeException exception) {
			FetchResult failure = FetchResult.failure(null, "UNEXPECTED_FETCH_ERROR");
			newFuture.complete(failure);
			return failure;
		} finally {
			inFlightDownloads.remove(dedupKey, newFuture);
		}
	}

	int cachedEntryCount() {
		return recentDownloads.size();
	}

	long cachedBytes() {
		return recentDownloads.bytes();
	}

	public void clearDedupCache() {
		recentDownloads.clear();
		inFlightDownloads.clear();
	}

	private FetchResult downloadWithRedirects(URI initialUri, String shop) {
		URI currentUri = initialUri;
		for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
			if (hop > 0) {
				String validationError = validateTargetUri(currentUri, shop);
				if (validationError != null) {
					return FetchResult.failure(null, validationError);
				}
			}
			String host = currentUri.getHost().toLowerCase(Locale.ROOT);
			URI requestUri = currentUri;
			HopOutcome outcome = circuitBreaker.executeWithPermit(host, () -> executeSingleHop(requestUri, host),
					() -> HopOutcome.done(FetchResult.failure(null, "HOST_CIRCUIT_OPEN")),
					() -> HopOutcome.done(FetchResult.failure(null, "HOST_CONCURRENCY_LIMIT")));

			if (outcome.redirectTarget() != null) {
				if (hop == MAX_REDIRECT_HOPS) {
					return FetchResult.failure(outcome.statusCode(), "TOO_MANY_REDIRECTS");
				}
				currentUri = outcome.redirectTarget();
				continue;
			}
			return outcome.result();
		}
		return FetchResult.failure(null, "TOO_MANY_REDIRECTS");
	}

	private HopOutcome executeSingleHop(URI requestUri, String host) {
		Duration budget = Duration.ofMillis(properties.mirrorReadTimeoutMs());
		long deadlineNanos = System.nanoTime() + budget.toNanos();
		try (HttpResponseData response = httpTransport.get(requestUri, budget)) {
			AtomicBoolean deadlineHit = new AtomicBoolean(false);
			// The transport only bounds the header phase. The body is bounded by this total
			// per-hop deadline:
			// the watchdog aborts the connection so a blocked read fails instead of holding
			// the worker.
			ScheduledFuture<?> guard = ApacheImageHttpTransport.schedule(() -> {
				deadlineHit.set(true);
				response.abort();
			}, Duration.ofNanos(Math.max(1L, deadlineNanos - System.nanoTime())));
			try {
				return handleResponse(requestUri, host, response, deadlineNanos);
			} catch (IOException exception) {
				if (deadlineHit.get()) {
					throw new SocketTimeoutException("Image download exceeded its deadline");
				}
				throw exception;
			} finally {
				guard.cancel(false);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return HopOutcome.done(FetchResult.failure(null, "FETCH_INTERRUPTED"));
		} catch (InterruptedIOException exception) {
			return HopOutcome.done(FetchResult.failure(null, "FETCH_TIMEOUT"));
		} catch (IOException exception) {
			if (causedByForbiddenAddress(exception)) {
				return HopOutcome.done(FetchResult.failure(null, "SSRF_BLOCKED_PRIVATE_IP"));
			}
			return HopOutcome.done(FetchResult.failure(null, "FETCH_IO_ERROR"));
		}
	}

	private HopOutcome handleResponse(URI requestUri, String host, HttpResponseData response, long deadlineNanos)
			throws IOException {
		int status = response.statusCode();
		if (isRedirectStatus(status)) {
			String location = response.location();
			if (location == null || location.isBlank()) {
				return HopOutcome.done(FetchResult.failure(status, "INVALID_REDIRECT_LOCATION"));
			}
			URI nextUri;
			try {
				nextUri = requestUri.resolve(location.trim());
			} catch (IllegalArgumentException exception) {
				return HopOutcome.done(FetchResult.failure(status, "INVALID_REDIRECT_LOCATION"));
			}
			if (!"https".equalsIgnoreCase(nextUri.getScheme()) || nextUri.getHost() == null
					|| nextUri.getHost().isBlank() || nextUri.getRawUserInfo() != null) {
				return HopOutcome.done(FetchResult.failure(status, "REDIRECT_UNSUPPORTED_SCHEME"));
			}
			return HopOutcome.redirect(status, nextUri);
		}

		// Only explicit overload signals trip the host circuit. A 403/404 depends on
		// the (user supplied) path, so
		// counting it would let one user's bad URLs block mirroring for every user of
		// that host.
		if (status == 429 || status == 503) {
			circuitBreaker.recordRateLimited(host);
			return HopOutcome.done(FetchResult.failure(status, "HTTP_" + status));
		}
		if (status < 200 || status >= 300) {
			return HopOutcome.done(FetchResult.failure(status, "HTTP_" + status));
		}

		String contentType = response.contentType();
		if (contentType == null || !contentType.trim().toLowerCase(Locale.ROOT).startsWith("image/")) {
			return HopOutcome.done(FetchResult.failure(status, "INVALID_CONTENT_TYPE"));
		}

		long maxBytes = properties.maxFileSizeBytes();
		if (response.contentLength() > maxBytes) {
			return HopOutcome.done(FetchResult.failure(status, "IMAGE_TOO_LARGE"));
		}

		byte[] bytes = readBounded(response.body(), maxBytes, deadlineNanos);
		if (bytes == null) {
			return HopOutcome.done(FetchResult.failure(status, "IMAGE_TOO_LARGE"));
		}
		if (bytes.length == 0) {
			return HopOutcome.done(FetchResult.failure(status, "EMPTY_IMAGE_BODY"));
		}
		circuitBreaker.recordSuccess(host);
		return HopOutcome.done(FetchResult.ok(bytes, contentType.trim()));
	}

	private static boolean causedByForbiddenAddress(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof ForbiddenAddressException) {
				return true;
			}
			if (current.getCause() == current) {
				break;
			}
		}
		return false;
	}

	private URI parseValidatedUri(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			return null;
		}
		URI uri;
		try {
			uri = URI.create(rawUrl.trim());
		} catch (IllegalArgumentException exception) {
			return null;
		}
		if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
				|| uri.getRawUserInfo() != null) {
			return null;
		}
		return uri;
	}

	private String validateTargetUri(URI uri, String shop) {
		String host = uri.getHost().trim().toLowerCase(Locale.ROOT);
		if (properties.isHostDisabled(host)) {
			return "HOST_MIRRORING_DISABLED";
		}
		if (!isAllowedHostForShop(shop, host)) {
			return "DISALLOWED_SHOP_IMAGE_HOST";
		}
		if (circuitBreaker.isOpen(host)) {
			return "HOST_CIRCUIT_OPEN";
		}
		return validateDnsAddresses(host);
	}

	private String validateDnsAddresses(String host) {
		InetAddress[] addresses;
		try {
			addresses = dnsResolver.resolve(host);
		} catch (UnknownHostException exception) {
			return "DNS_RESOLUTION_FAILED";
		}
		if (addresses == null || addresses.length == 0) {
			return "DNS_RESOLUTION_FAILED";
		}
		for (InetAddress address : addresses) {
			if (isForbiddenAddress(address)) {
				return "SSRF_BLOCKED_PRIVATE_IP";
			}
		}
		return null;
	}

	public static boolean isForbiddenAddress(InetAddress address) {
		if (address == null) {
			return true;
		}
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
			return true;
		}
		byte[] bytes = address.getAddress();
		if (bytes.length == 4) {
			return isForbiddenIpv4(bytes);
		}
		if (bytes.length == 16) {
			// IPv6 ULA fc00::/7
			if ((bytes[0] & 0xFE) == 0xFC) {
				return true;
			}
			// IPv4-mapped IPv6 ::ffff:0:0/96
			if (isIpv4MappedIpv6(bytes)) {
				return isForbiddenIpv4(Arrays.copyOfRange(bytes, 12, 16));
			}
		}
		return false;
	}

	private static boolean isForbiddenIpv4(byte[] bytes) {
		int b0 = bytes[0] & 0xFF;
		int b1 = bytes[1] & 0xFF;
		// 0.0.0.0/8 (current network)
		if (b0 == 0) {
			return true;
		}
		// 10.0.0.0/8 (private)
		if (b0 == 10) {
			return true;
		}
		// 100.64.0.0/10 (CGNAT)
		if (b0 == 100 && (b1 & 0xC0) == 64) {
			return true;
		}
		// 127.0.0.0/8 (loopback)
		if (b0 == 127) {
			return true;
		}
		// 169.254.0.0/16 (link-local / cloud metadata)
		if (b0 == 169 && b1 == 254) {
			return true;
		}
		// 172.16.0.0/12 (private)
		if (b0 == 172 && b1 >= 16 && b1 <= 31) {
			return true;
		}
		// 192.168.0.0/16 (private)
		if (b0 == 192 && b1 == 168) {
			return true;
		}
		// 198.18.0.0/15 (benchmark)
		if (b0 == 198 && (b1 == 18 || b1 == 19)) {
			return true;
		}
		// 224.0.0.0/4 multicast & 240.0.0.0/4 reserved/broadcast
		return b0 >= 224;
	}

	private static boolean isIpv4MappedIpv6(byte[] bytes) {
		for (int i = 0; i < 10; i++) {
			if (bytes[i] != 0) {
				return false;
			}
		}
		return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
	}

	private static boolean isRedirectStatus(int status) {
		return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
	}

	private static byte[] readBounded(InputStream body, long maxBytes, long deadlineNanos) throws IOException {
		if (body == null) {
			return new byte[0];
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[READ_BUFFER_SIZE];
		long total = 0L;
		int read;
		while ((read = body.read(buffer)) != -1) {
			if (System.nanoTime() - deadlineNanos > 0) {
				throw new SocketTimeoutException("Image download exceeded its deadline");
			}
			total += read;
			if (total > maxBytes) {
				return null;
			}
			out.write(buffer, 0, read);
		}
		return out.toByteArray();
	}

	static HttpTransport createDefaultTransport(StorageProperties properties, DnsResolver dnsResolver,
			Predicate<InetAddress> forbiddenAddress) {
		return ApacheImageHttpTransport.create(properties, dnsResolver, forbiddenAddress);
	}

	@FunctionalInterface
	public interface DnsResolver {
		InetAddress[] resolve(String host) throws UnknownHostException;
	}

	@FunctionalInterface
	public interface HttpTransport {
		HttpResponseData get(URI uri, Duration readTimeout) throws IOException, InterruptedException;
	}

	public record HttpResponseData(int statusCode, String contentType, long contentLength, String location,
			InputStream body, Runnable abortAction) implements AutoCloseable {
		public HttpResponseData(int statusCode, String contentType, long contentLength, String location,
				InputStream body) {
			this(statusCode, contentType, contentLength, location, body, null);
		}

		/**
		 * Thread-safe: forcibly terminates the connection so a blocked read fails
		 * promptly.
		 */
		public void abort() {
			if (abortAction != null) {
				abortAction.run();
				return;
			}
			if (body != null) {
				try {
					body.close();
				} catch (IOException exception) {
					throw new UncheckedIOException(exception);
				}
			}
		}

		@Override
		public void close() throws IOException {
			if (body != null) {
				body.close();
			}
		}
	}

	public record FetchResult(boolean success, byte[] bytes, String contentType, Integer statusCode,
			String errorReason) {
		public static FetchResult ok(byte[] bytes, String contentType) {
			return new FetchResult(true, Arrays.copyOf(bytes, bytes.length), contentType, 200, null);
		}

		public static FetchResult failure(Integer statusCode, String errorReason) {
			return new FetchResult(false, null, null, statusCode, errorReason);
		}

		/**
		 * Whether retrying later can plausibly succeed (host cooling down, network or
		 * upstream trouble) as opposed to a permanent property of the URL or image.
		 */
		public boolean transientFailure() {
			if (success) {
				return false;
			}
			if (errorReason != null) {
				switch (errorReason) {
					case "HOST_CIRCUIT_OPEN", "HOST_CONCURRENCY_LIMIT", "FETCH_IO_ERROR", "FETCH_TIMEOUT",
							"FETCH_INTERRUPTED", "DNS_RESOLUTION_FAILED", "UNEXPECTED_FETCH_ERROR",
							"DEDUP_JOIN_FAILED" :
						return true;
					default :
						break;
				}
			}
			return statusCode != null && (statusCode == 429 || (statusCode >= 500 && statusCode < 600));
		}

		FetchResult copy() {
			if (!success || bytes == null) {
				return this;
			}
			return new FetchResult(true, Arrays.copyOf(bytes, bytes.length), contentType, statusCode, null);
		}
	}

	private record CachedDownload(FetchResult result, Instant expiresAt, long size) {
	}

	/**
	 * Short-lived download dedup cache bounded by entry count and total stored
	 * bytes. Expired entries are purged on every insertion (not only when the same
	 * key is read again) and the oldest entries are evicted to stay in budget.
	 */
	private static final class DownloadCache {
		private final LinkedHashMap<String, CachedDownload> entries = new LinkedHashMap<>();
		private long totalBytes;

		synchronized FetchResult get(String key, Instant now) {
			CachedDownload cached = entries.get(key);
			if (cached == null) {
				return null;
			}
			if (now.isAfter(cached.expiresAt())) {
				remove(key);
				return null;
			}
			return cached.result();
		}

		synchronized void put(String key, FetchResult result, Instant now, long ttlSeconds, long maxBytes,
				int maxEntries) {
			purgeExpired(now);
			remove(key);
			long size = result.bytes() == null ? 0L : result.bytes().length;
			if (size > maxBytes) {
				return;
			}
			while (!entries.isEmpty() && (entries.size() >= maxEntries || totalBytes + size > maxBytes)) {
				Iterator<String> oldest = entries.keySet().iterator();
				remove(oldest.next());
			}
			entries.put(key, new CachedDownload(result.copy(), now.plusSeconds(ttlSeconds), size));
			totalBytes += size;
		}

		synchronized int size() {
			return entries.size();
		}

		synchronized long bytes() {
			return totalBytes;
		}

		synchronized void clear() {
			entries.clear();
			totalBytes = 0L;
		}

		private void purgeExpired(Instant now) {
			Iterator<Map.Entry<String, CachedDownload>> iterator = entries.entrySet().iterator();
			while (iterator.hasNext()) {
				CachedDownload entry = iterator.next().getValue();
				if (now.isAfter(entry.expiresAt())) {
					totalBytes -= entry.size();
					iterator.remove();
				}
			}
		}

		private void remove(String key) {
			CachedDownload removed = entries.remove(key);
			if (removed != null) {
				totalBytes -= removed.size();
			}
		}
	}

	private record HopOutcome(FetchResult result, Integer statusCode, URI redirectTarget) {
		private static HopOutcome done(FetchResult result) {
			return new HopOutcome(result, result.statusCode(), null);
		}

		private static HopOutcome redirect(int statusCode, URI redirectTarget) {
			return new HopOutcome(null, statusCode, redirectTarget);
		}
	}
}
