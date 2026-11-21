package com.ject.ziper.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

class SupabaseObjectStorageClientTest {

	@Test
	void Supabase스토리지클라이언트_업로드와서명URL발급과삭제를호출하면_서비스롤헤더로통신하고404삭제를멱등처리한다() throws Exception {
		AtomicReference<String> uploadedPath = new AtomicReference<>();
		AtomicReference<String> uploadedAuth = new AtomicReference<>();
		AtomicReference<String> uploadedContentType = new AtomicReference<>();
		AtomicReference<byte[]> uploadedBody = new AtomicReference<>();
		AtomicReference<String> signRequestBody = new AtomicReference<>();
		AtomicReference<String> deletedPath = new AtomicReference<>();

		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/storage/v1/object/product-images/", exchange -> {
			if ("POST".equals(exchange.getRequestMethod())) {
				uploadedPath.set(exchange.getRequestURI().getRawPath());
				uploadedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
				uploadedContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
				uploadedBody.set(exchange.getRequestBody().readAllBytes());
				byte[] response = "{\"Key\":\"product-images/thumbnails/2026/11/test.webp\"}"
						.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().set("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, response.length);
				exchange.getResponseBody().write(response);
			} else if ("DELETE".equals(exchange.getRequestMethod())) {
				deletedPath.set(exchange.getRequestURI().getRawPath());
				exchange.sendResponseHeaders(404, -1);
			}
			exchange.close();
		});
		server.createContext("/storage/v1/object/sign/product-images/", exchange -> {
			signRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] response = "{\"signedURL\":\"/object/sign/product-images/thumbnails/2026/11/test.webp?token=abc\"}"
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
			StorageProperties properties = new StorageProperties("supabase", baseUrl, "super-secret-role-key",
					"product-images", 900L, StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES, true, List.of(), false,
					5000L, 5, 5000, 10000, 2, 0L, 3, 300L, 30L, null, null, null, null, null, null, null);

			assertThat(properties.toString()).doesNotContain("super-secret-role-key").contains("[REDACTED]");

			SupabaseObjectStorageClient client = new SupabaseObjectStorageClient(properties, RestClient.builder());
			byte[] payload = "webp-bytes".getBytes(StandardCharsets.UTF_8);

			client.putObject("thumbnails/2026/11/test.webp", payload, "image/webp");
			String signedUrl = client.createSignedUrl("thumbnails/2026/11/test.webp", Duration.ofSeconds(900));
			client.deleteObject("thumbnails/2026/11/test.webp");

			assertThat(uploadedPath.get()).isEqualTo("/storage/v1/object/product-images/thumbnails/2026/11/test.webp");
			assertThat(uploadedAuth.get()).isEqualTo("Bearer super-secret-role-key");
			assertThat(uploadedContentType.get()).isEqualTo("image/webp");
			assertThat(uploadedBody.get()).isEqualTo(payload);
			assertThat(signRequestBody.get()).contains("900");
			assertThat(signedUrl).isEqualTo(
					baseUrl + "/storage/v1/object/sign/product-images/thumbnails/2026/11/test.webp?token=abc");
			assertThat(deletedPath.get()).isEqualTo("/storage/v1/object/product-images/thumbnails/2026/11/test.webp");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void 여러키를일괄서명하는상황_서명을요청하면_한번의호출로요청하고경로별오류키는결과에서제외한다() throws Exception {
		AtomicReference<String> signRequestBody = new AtomicReference<>();
		AtomicInteger signCalls = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/storage/v1/object/sign/product-images", exchange -> {
			signCalls.incrementAndGet();
			signRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] response = ("[{\"error\":null,\"path\":\"a/1.webp\",\"signedURL\":\"/object/sign/product-images/a/1.webp?token=t1\"},"
					+ "{\"error\":\"Object not found\",\"path\":\"a/missing.webp\",\"signedURL\":null}]")
					.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		try {
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
			StorageProperties properties = new StorageProperties("supabase", baseUrl, "key", "product-images", 900L,
					StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES, true, List.of(), false, 5000L, 5, 5000, 10000, 2, 0L,
					3, 300L, 30L, null, null, null, null, null, null, null);
			SupabaseObjectStorageClient client = new SupabaseObjectStorageClient(properties, RestClient.builder());

			var urls = client.createSignedUrls(List.of("a/1.webp", "a/missing.webp"), Duration.ofSeconds(600));

			assertThat(signCalls.get()).isEqualTo(1);
			assertThat(signRequestBody.get()).contains("\"paths\"").contains("a/1.webp").contains("a/missing.webp")
					.contains("600");
			assertThat(urls).containsOnlyKeys("a/1.webp");
			assertThat(urls.get("a/1.webp"))
					.isEqualTo(baseUrl + "/storage/v1/object/sign/product-images/a/1.webp?token=t1");
		} finally {
			server.stop(0);
		}
	}

	@Test
	void Supabase응답이멈추면_업로드를호출할때_읽기타임아웃후예외를던진다() throws Exception {
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
			String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
			StorageProperties properties = new StorageProperties("supabase", baseUrl, "key", "product-images", 900L,
					StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES, true, List.of(), false, 5000L, 5, 5000, 10000, 2, 0L,
					3, 300L, 30L, null, null, null, null, null, 500, 500);
			SupabaseObjectStorageClient client = new SupabaseObjectStorageClient(properties, RestClient.builder());

			long start = System.nanoTime();
			assertThatThrownBy(() -> client.putObject("a/1.webp", new byte[]{1}, "image/webp"))
					.isInstanceOf(IllegalStateException.class);
			assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5000);
		} finally {
			release.countDown();
			server.stop(0);
		}
	}
}
