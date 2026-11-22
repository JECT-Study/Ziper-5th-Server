package com.ject.ziper.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

class R2ObjectStorageClientTest {

	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-22T04:30:00Z"), ZoneOffset.UTC);

	@Test
	void R2스토리지클라이언트_업로드와서명URL발급과삭제를호출하면_SigV4서명헤더로통신하고404삭제를멱등처리한다() throws Exception {
		AtomicInteger httpCallCount = new AtomicInteger();
		AtomicReference<String> uploadedPath = new AtomicReference<>();
		AtomicReference<String> uploadedAuth = new AtomicReference<>();
		AtomicReference<String> uploadedContentType = new AtomicReference<>();
		AtomicReference<String> uploadedSha256 = new AtomicReference<>();
		AtomicReference<String> uploadedAmzDate = new AtomicReference<>();
		AtomicReference<byte[]> uploadedBody = new AtomicReference<>();
		AtomicReference<String> deletedPath = new AtomicReference<>();
		AtomicReference<String> deletedAuth = new AtomicReference<>();

		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/ziper-product-images-production/", exchange -> {
			httpCallCount.incrementAndGet();
			if ("PUT".equals(exchange.getRequestMethod())) {
				uploadedPath.set(exchange.getRequestURI().getRawPath());
				uploadedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
				uploadedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
				uploadedSha256.set(exchange.getRequestHeaders().getFirst("x-amz-content-sha256"));
				uploadedAmzDate.set(exchange.getRequestHeaders().getFirst("x-amz-date"));
				uploadedBody.set(exchange.getRequestBody().readAllBytes());
				exchange.sendResponseHeaders(200, -1);
			} else if ("DELETE".equals(exchange.getRequestMethod())) {
				deletedPath.set(exchange.getRequestURI().getRawPath());
				deletedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
				exchange.sendResponseHeaders(404, -1);
			}
			exchange.close();
		});
		server.start();
		try {
			String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
			StorageProperties properties = createR2Properties(endpoint, "test-access-key-id", "test-secret-access-key",
					3000, 15000);

			assertThat(properties.toString()).doesNotContain("test-access-key-id")
					.doesNotContain("test-secret-access-key").contains("r2AccessKeyId=[REDACTED]")
					.contains("r2SecretAccessKey=[REDACTED]");

			R2ObjectStorageClient client = new R2ObjectStorageClient(properties, RestClient.builder(), FIXED_CLOCK);
			byte[] payload = "webp-bytes".getBytes(StandardCharsets.UTF_8);

			client.putObject("thumbnails/2026/11/test.webp", payload, "image/webp");
			int callsBeforePresign = httpCallCount.get();
			String signedUrl = client.createSignedUrl("thumbnails/2026/11/test.webp", Duration.ofSeconds(900));
			int callsAfterPresign = httpCallCount.get();
			client.deleteObject("thumbnails/2026/11/test.webp");

			assertThat(uploadedPath.get()).isEqualTo("/ziper-product-images-production/thumbnails/2026/11/test.webp");
			assertThat(uploadedAuth.get()).startsWith(
					"AWS4-HMAC-SHA256 Credential=test-access-key-id/20261122/auto/s3/aws4_request, SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date, Signature=");
			assertThat(uploadedContentType.get()).isEqualTo("image/webp");
			assertThat(uploadedSha256.get()).isEqualTo(sha256Hex(payload));
			assertThat(uploadedAmzDate.get()).isEqualTo("20261122T043000Z");
			assertThat(uploadedBody.get()).isEqualTo(payload);

			assertThat(callsAfterPresign).isEqualTo(callsBeforePresign);
			URI parsedSigned = URI.create(signedUrl);
			assertThat(parsedSigned.getPath())
					.isEqualTo("/ziper-product-images-production/thumbnails/2026/11/test.webp");
			assertThat(parsedSigned.getRawQuery()).contains("X-Amz-Algorithm=AWS4-HMAC-SHA256")
					.contains("X-Amz-Credential=test-access-key-id%2F20261122%2Fauto%2Fs3%2Faws4_request")
					.contains("X-Amz-Date=20261122T043000Z").contains("X-Amz-Expires=900")
					.contains("X-Amz-SignedHeaders=host").contains("X-Amz-Signature=");

			assertThat(deletedPath.get()).isEqualTo("/ziper-product-images-production/thumbnails/2026/11/test.webp");
			assertThat(deletedAuth.get()).startsWith(
					"AWS4-HMAC-SHA256 Credential=test-access-key-id/20261122/auto/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void 여러키를일괄서명하는상황_R2서명을요청하면_네트워크호출없이모든유효키의서명URL을생성한다() {
		StorageProperties properties = createR2Properties("https://example-account.r2.cloudflarestorage.com",
				"access-key-32-chars-placeholder12", "secret-key-placeholder", 3000, 15000);
		R2ObjectStorageClient client = new R2ObjectStorageClient(properties, RestClient.builder(), FIXED_CLOCK);

		Map<String, String> urls = client.createSignedUrls(List.of("a/1.webp", "b/2.webp", "a/1.webp", " "),
				Duration.ofSeconds(600));

		assertThat(urls).containsOnlyKeys("a/1.webp", "b/2.webp");
		assertThat(urls.get("a/1.webp"))
				.startsWith(
						"https://example-account.r2.cloudflarestorage.com/ziper-product-images-production/a/1.webp?")
				.contains("X-Amz-Expires=600");
		assertThat(urls.get("b/2.webp"))
				.startsWith(
						"https://example-account.r2.cloudflarestorage.com/ziper-product-images-production/b/2.webp?")
				.contains("X-Amz-Expires=600");
	}

	@Test
	void R2응답이멈추면_업로드를호출할때_읽기타임아웃후예외를던진다() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		CountDownLatch release = new CountDownLatch(1);
		server.createContext("/", exchange -> {
			try {
				release.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
			exchange.close();
		});
		server.start();
		try {
			String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
			StorageProperties properties = createR2Properties(endpoint, "ak", "sk", 500, 500);
			R2ObjectStorageClient client = new R2ObjectStorageClient(properties, RestClient.builder(), FIXED_CLOCK);

			long start = System.nanoTime();
			assertThatThrownBy(() -> client.putObject("a/1.webp", new byte[]{1}, "image/webp"))
					.isInstanceOf(IllegalStateException.class);
			assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5000);
		} finally {
			release.countDown();
			server.stop(0);
		}
	}

	@Test
	void R2스토리지설정_빈더로생성하면_R2ObjectStorageClient빈을반환하고미설정시예외를던진다() {
		StorageConfiguration configuration = new StorageConfiguration();
		StorageProperties configured = createR2Properties("https://account.r2.cloudflarestorage.com", "ak", "sk", 3000,
				15000);
		ObjectStorageClient client = configuration.objectStorageClient(configured, RestClient.builder());
		assertThat(client).isInstanceOf(R2ObjectStorageClient.class);

		StorageProperties missingCredentials = createR2Properties("https://account.r2.cloudflarestorage.com", "", "",
				3000, 15000);
		R2ObjectStorageClient unconfiguredClient = new R2ObjectStorageClient(missingCredentials, RestClient.builder());
		assertThatThrownBy(() -> unconfiguredClient.createSignedUrl("a/1.webp", Duration.ofSeconds(60)))
				.isInstanceOf(IllegalStateException.class);
	}

	private static StorageProperties createR2Properties(String endpoint, String accessKeyId, String secretAccessKey,
			int connectTimeoutMs, int readTimeoutMs) {
		return new StorageProperties("r2", "", "", "ziper-product-images-production", 900L,
				StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES, true, List.of(), false, 5000L, 5, 5000, 10000, 2, 0L, 3,
				300L, 30L, StorageProperties.DEFAULT_DEDUP_MAX_BYTES, 5, 60L, 3600L, 600L, 3000, 15000, endpoint,
				accessKeyId, secretAccessKey, connectTimeoutMs, readTimeoutMs);
	}

	private static String sha256Hex(byte[] bytes) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
		StringBuilder builder = new StringBuilder(digest.length * 2);
		for (byte b : digest) {
			builder.append(Character.forDigit((b >> 4) & 0xF, 16));
			builder.append(Character.forDigit(b & 0xF, 16));
		}
		return builder.toString();
	}
}
