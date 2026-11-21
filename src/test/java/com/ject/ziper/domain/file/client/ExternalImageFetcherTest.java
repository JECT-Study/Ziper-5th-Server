package com.ject.ziper.domain.file.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.ject.ziper.domain.file.client.ExternalImageFetcher.FetchResult;
import com.ject.ziper.domain.file.client.ExternalImageFetcher.HttpTransport;
import com.ject.ziper.domain.file.client.ExternalImageFetcher.HttpResponseData;
import com.ject.ziper.global.storage.StorageProperties;
import com.sun.net.httpserver.HttpServer;

class ExternalImageFetcherTest {
	private static final InetAddress PUBLIC_IP = mustParse("93.184.216.34");
	private static final InetAddress LOOPBACK_IP = mustParse("127.0.0.1");
	private static final InetAddress METADATA_IP = mustParse("169.254.169.254");
	private static final InetAddress CGNAT_IP = mustParse("100.64.0.1");
	private static final InetAddress IPV6_ULA = mustParse("fd12:3456:789a::1");

	@Test
	void 사설IP와비HTTPS스킴과허용외호스트_이미지다운로드를요청하면_SSRF방어로차단한다() {
		StorageProperties properties = StorageProperties.defaultsForMemory();
		HostMirrorCircuitBreaker circuitBreaker = new HostMirrorCircuitBreaker(properties);
		AtomicInteger transportCalls = new AtomicInteger(0);

		ExternalImageFetcher privateIpFetcher = new ExternalImageFetcher(properties, circuitBreaker,
				host -> new InetAddress[]{LOOPBACK_IP}, (uri, timeout) -> {
					transportCalls.incrementAndGet();
					return new HttpResponseData(200, "image/jpeg", 4, null,
							new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
				}, Clock.systemUTC());

		assertThat(privateIpFetcher.fetch("https://unknown.example/a.jpg", "hash1", null).errorReason())
				.isEqualTo("SSRF_BLOCKED_PRIVATE_IP");
		assertThat(privateIpFetcher.fetch("http://unknown.example/a.jpg", "hash2", null).errorReason())
				.isEqualTo("INVALID_OR_UNSUPPORTED_URL");
		assertThat(privateIpFetcher.fetch("https://evil.example/a.jpg", "hash3", "무신사").errorReason())
				.isEqualTo("DISALLOWED_SHOP_IMAGE_HOST");
		assertThat(transportCalls.get()).isZero();

		assertThat(ExternalImageFetcher.isForbiddenAddress(LOOPBACK_IP)).isTrue();
		assertThat(ExternalImageFetcher.isForbiddenAddress(METADATA_IP)).isTrue();
		assertThat(ExternalImageFetcher.isForbiddenAddress(CGNAT_IP)).isTrue();
		assertThat(ExternalImageFetcher.isForbiddenAddress(IPV6_ULA)).isTrue();
		assertThat(ExternalImageFetcher.isForbiddenAddress(PUBLIC_IP)).isFalse();
	}

	@Test
	void 리다이렉트대상이사설IP이거나홉제한초과_이미지다운로드를요청하면_재검사후차단한다() {
		StorageProperties properties = StorageProperties.defaultsForMemory();
		HostMirrorCircuitBreaker circuitBreaker = new HostMirrorCircuitBreaker(properties);

		ExternalImageFetcher redirectToPrivate = new ExternalImageFetcher(properties, circuitBreaker,
				host -> host.equals("internal.example") ? new InetAddress[]{METADATA_IP} : new InetAddress[]{PUBLIC_IP},
				(uri, timeout) -> new HttpResponseData(302, null, -1, "https://internal.example/secret.jpg", null),
				Clock.systemUTC());

		FetchResult privateRedirectResult = redirectToPrivate.fetch("https://cdn.example/start.jpg", "redir1", null);
		assertThat(privateRedirectResult.success()).isFalse();
		assertThat(privateRedirectResult.errorReason()).isEqualTo("SSRF_BLOCKED_PRIVATE_IP");

		ExternalImageFetcher tooManyHops = new ExternalImageFetcher(properties, circuitBreaker,
				host -> new InetAddress[]{PUBLIC_IP},
				(uri, timeout) -> new HttpResponseData(302, null, -1, "https://cdn.example/next", null),
				Clock.systemUTC());

		FetchResult hopLimitResult = tooManyHops.fetch("https://cdn.example/start.jpg", "redir2", null);
		assertThat(hopLimitResult.success()).isFalse();
		assertThat(hopLimitResult.errorReason()).isEqualTo("TOO_MANY_REDIRECTS");
	}

	@Test
	void 최대용량초과또는비이미지응답_이미지다운로드를요청하면_안전하게거부한다() {
		StorageProperties properties = new StorageProperties("memory", "", "", "product-images", 900L, 1024L, true,
				List.of(), false, 5000L, 5, 5000, 10000, 2, 0L, 3, 300L, 30L, null, null, null, null, null, null, null);
		HostMirrorCircuitBreaker circuitBreaker = new HostMirrorCircuitBreaker(properties);

		ExternalImageFetcher largeBodyFetcher = new ExternalImageFetcher(properties, circuitBreaker,
				host -> new InetAddress[]{PUBLIC_IP}, (uri, timeout) -> new HttpResponseData(200, "image/jpeg", -1,
						null, new ByteArrayInputStream(new byte[2048])),
				Clock.systemUTC());

		FetchResult oversized = largeBodyFetcher.fetch("https://image.msscdn.net/item.jpg", "big1", "무신사");
		assertThat(oversized.success()).isFalse();
		assertThat(oversized.errorReason()).isEqualTo("IMAGE_TOO_LARGE");

		ExternalImageFetcher htmlFetcher = new ExternalImageFetcher(properties, circuitBreaker,
				host -> new InetAddress[]{PUBLIC_IP}, (uri, timeout) -> new HttpResponseData(200, "text/html", 10, null,
						new ByteArrayInputStream(new byte[10])),
				Clock.systemUTC());

		FetchResult htmlResult = htmlFetcher.fetch("https://image.msscdn.net/item.jpg", "html1", "무신사");
		assertThat(htmlResult.success()).isFalse();
		assertThat(htmlResult.errorReason()).isEqualTo("INVALID_CONTENT_TYPE");
	}

	@Test
	void 동일한원본URL해시_동시및연속다운로드를요청하면_실제HTTP요청을한번만수행한다() throws Exception {
		StorageProperties properties = StorageProperties.defaultsForMemory();
		HostMirrorCircuitBreaker circuitBreaker = new HostMirrorCircuitBreaker(properties);
		AtomicInteger transportCalls = new AtomicInteger(0);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		ExternalImageFetcher fetcher = new ExternalImageFetcher(properties, circuitBreaker,
				host -> new InetAddress[]{PUBLIC_IP}, (uri, timeout) -> {
					transportCalls.incrementAndGet();
					entered.countDown();
					release.await();
					return new HttpResponseData(200, "image/png", 4, null,
							new ByteArrayInputStream(new byte[]{10, 20, 30, 40}));
				}, Clock.systemUTC());

		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			List<Callable<FetchResult>> tasks = new ArrayList<>();
			for (int i = 0; i < 3; i++) {
				tasks.add(() -> fetcher.fetch("https://image.msscdn.net/same.png", "same-hash", "무신사"));
			}
			List<Future<FetchResult>> futures = new ArrayList<>();
			for (Callable<FetchResult> task : tasks) {
				futures.add(pool.submit(task));
			}
			entered.await();
			release.countDown();
			for (Future<FetchResult> future : futures) {
				FetchResult result = future.get();
				assertThat(result.success()).isTrue();
				assertThat(result.bytes()).containsExactly(10, 20, 30, 40);
			}
			FetchResult cachedAgain = fetcher.fetch("https://image.msscdn.net/same.png", "same-hash", "무신사");
			assertThat(cachedAgain.success()).isTrue();
			assertThat(transportCalls.get()).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 호스트에서429또는503연속3회발생_이미지다운로드를요청하면_서킷브레이커가차단하고쿨다운후복구된다() {
		MutableClock clock = new MutableClock(Instant.parse("2026-11-21T00:00:00Z"));
		StorageProperties properties = new StorageProperties("memory", "", "", "product-images", 900L,
				StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES, true, List.of(), false, 5000L, 5, 5000, 10000, 2, 0L, 3,
				60L, 0L, null, null, null, null, null, null, null);
		HostMirrorCircuitBreaker circuitBreaker = new HostMirrorCircuitBreaker(properties, clock);
		AtomicInteger transportCalls = new AtomicInteger(0);
		AtomicInteger responseStatus = new AtomicInteger(429);

		ExternalImageFetcher fetcher = new ExternalImageFetcher(properties, circuitBreaker,
				host -> new InetAddress[]{PUBLIC_IP}, (uri, timeout) -> {
					transportCalls.incrementAndGet();
					int status = responseStatus.get();
					return new HttpResponseData(status, "image/jpeg", 4, null,
							new ByteArrayInputStream(new byte[]{1, 2, 3, 4}));
				}, clock);

		assertThat(fetcher.fetch("https://cf.product-image.s.zigzag.kr/1.jpg", "h1", "지그재그").errorReason())
				.isEqualTo("HTTP_429");
		responseStatus.set(503);
		assertThat(fetcher.fetch("https://cf.product-image.s.zigzag.kr/2.jpg", "h2", "지그재그").errorReason())
				.isEqualTo("HTTP_503");
		responseStatus.set(429);
		assertThat(fetcher.fetch("https://cf.product-image.s.zigzag.kr/3.jpg", "h3", "지그재그").errorReason())
				.isEqualTo("HTTP_429");
		assertThat(transportCalls.get()).isEqualTo(3);

		FetchResult blocked = fetcher.fetch("https://cf.product-image.s.zigzag.kr/4.jpg", "h4", "지그재그");
		assertThat(blocked.success()).isFalse();
		assertThat(blocked.errorReason()).isEqualTo("HOST_CIRCUIT_OPEN");
		assertThat(transportCalls.get()).isEqualTo(3);

		clock.advance(Duration.ofSeconds(61));
		responseStatus.set(200);
		FetchResult recovered = fetcher.fetch("https://cf.product-image.s.zigzag.kr/5.jpg", "h5", "지그재그");
		assertThat(recovered.success()).isTrue();
		assertThat(transportCalls.get()).isEqualTo(4);
	}

	@Test
	void 허용호스트가403을반복해서반환_이미지다운로드를요청하면_서킷을열지않고URL단위영구실패로처리한다() {
		StorageProperties properties = StorageProperties.defaultsForMemory();
		HostMirrorCircuitBreaker circuitBreaker = new HostMirrorCircuitBreaker(properties);
		AtomicInteger transportCalls = new AtomicInteger(0);
		ExternalImageFetcher fetcher = new ExternalImageFetcher(properties, circuitBreaker,
				host -> new InetAddress[]{PUBLIC_IP}, (uri, timeout) -> {
					transportCalls.incrementAndGet();
					return new HttpResponseData(403, "text/html", 0, null, new ByteArrayInputStream(new byte[0]));
				}, Clock.systemUTC());

		for (int i = 0; i < 6; i++) {
			FetchResult result = fetcher.fetch("https://image.msscdn.net/missing-" + i + ".jpg", "forbidden-" + i,
					"무신사");
			assertThat(result.errorReason()).isEqualTo("HTTP_403");
			assertThat(result.transientFailure()).isFalse();
		}
		assertThat(transportCalls.get()).isEqualTo(6);
		assertThat(circuitBreaker.isOpen("image.msscdn.net")).isFalse();
	}

	@Test
	void 분류대상실패사유_일시적여부를판정하면_일시적오류만재시도대상으로판정한다() {
		assertThat(FetchResult.failure(null, "HOST_CIRCUIT_OPEN").transientFailure()).isTrue();
		assertThat(FetchResult.failure(null, "HOST_CONCURRENCY_LIMIT").transientFailure()).isTrue();
		assertThat(FetchResult.failure(null, "FETCH_TIMEOUT").transientFailure()).isTrue();
		assertThat(FetchResult.failure(429, "HTTP_429").transientFailure()).isTrue();
		assertThat(FetchResult.failure(502, "HTTP_502").transientFailure()).isTrue();
		assertThat(FetchResult.failure(403, "HTTP_403").transientFailure()).isFalse();
		assertThat(FetchResult.failure(404, "HTTP_404").transientFailure()).isFalse();
		assertThat(FetchResult.failure(null, "IMAGE_TOO_LARGE").transientFailure()).isFalse();
		assertThat(FetchResult.failure(null, "DISALLOWED_SHOP_IMAGE_HOST").transientFailure()).isFalse();
		assertThat(FetchResult.failure(null, "SSRF_BLOCKED_PRIVATE_IP").transientFailure()).isFalse();
		assertThat(FetchResult.ok(new byte[]{1}, "image/png").transientFailure()).isFalse();
	}

	@Test
	void 느리게바이트를흘리는이미지호스트_다운로드하면_읽기타임아웃내에실패하고다음작업이이어진다() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/slow.jpg", exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream out = exchange.getResponseBody()) {
				for (int i = 0; i < 40; i++) {
					out.write(1);
					out.flush();
					Thread.sleep(500);
				}
			} catch (IOException | InterruptedException expected) {
				// The client aborts the connection at its deadline.
				exchange.close();
			}
		});
		server.createContext("/fast.jpg", exchange -> {
			exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
			exchange.sendResponseHeaders(200, 4);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(new byte[]{1, 2, 3, 4});
			}
		});
		server.start();
		try {
			StorageProperties properties = propertiesWithReadTimeout(1000);
			HttpTransport rewriting = rewriteToLocal(server, ExternalImageFetcher.createDefaultTransport(properties,
					InetAddress::getAllByName, address -> false));
			ExternalImageFetcher fetcher = new ExternalImageFetcher(properties,
					new HostMirrorCircuitBreaker(properties), host -> new InetAddress[]{PUBLIC_IP}, rewriting,
					Clock.systemUTC());

			long start = System.nanoTime();
			FetchResult slow = fetcher.fetch("https://slow.example/slow.jpg", "h-slow", null);
			long elapsedMs = (System.nanoTime() - start) / 1_000_000;

			assertThat(slow.success()).isFalse();
			assertThat(slow.errorReason()).isEqualTo("FETCH_TIMEOUT");
			assertThat(slow.transientFailure()).isTrue();
			assertThat(elapsedMs).as("읽기 타임아웃 1000ms 설정, 실제 소요 ms").isLessThan(4000);

			FetchResult next = fetcher.fetch("https://fast.example/fast.jpg", "h-fast", null);
			assertThat(next.success()).isTrue();
			assertThat(next.bytes()).containsExactly(1, 2, 3, 4);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void 검사시점과연결시점사이에DNS가내부주소로바뀌면_다운로드하면_연결하지않고차단한다() throws Exception {
		AtomicInteger serverHits = new AtomicInteger(0);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			serverHits.incrementAndGet();
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.start();
		try {
			StorageProperties properties = StorageProperties.defaultsForMemory();
			// Pre-flight lookup sees a public address; the lookup used for the actual
			// connection sees loopback.
			ExternalImageFetcher.DnsResolver connectTimeResolver = host -> new InetAddress[]{LOOPBACK_IP};
			HttpTransport rewriting = rewriteToLocalHost(server, "rebind.example", ExternalImageFetcher
					.createDefaultTransport(properties, connectTimeResolver, ExternalImageFetcher::isForbiddenAddress));
			ExternalImageFetcher fetcher = new ExternalImageFetcher(properties,
					new HostMirrorCircuitBreaker(properties), host -> new InetAddress[]{PUBLIC_IP}, rewriting,
					Clock.systemUTC());

			FetchResult result = fetcher.fetch("https://rebind.example/a.jpg", "h-rebind", null);

			assertThat(result.success()).isFalse();
			assertThat(result.errorReason()).isEqualTo("SSRF_BLOCKED_PRIVATE_IP");
			assertThat(serverHits.get()).isZero();
		} finally {
			server.stop(0);
		}
	}

	@Test
	void 검사를통과한주소가있을때_다운로드하면_리졸버가돌려준주소로만연결한다() throws Exception {
		AtomicInteger serverHits = new AtomicInteger(0);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			serverHits.incrementAndGet();
			exchange.getResponseHeaders().add("Content-Type", "image/png");
			exchange.sendResponseHeaders(200, 2);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(new byte[]{7, 8});
			}
		});
		server.start();
		try {
			StorageProperties properties = StorageProperties.defaultsForMemory();
			// "pinned.example" does not exist in real DNS: only the vetted resolver output
			// can make this connect.
			HttpTransport transport = ExternalImageFetcher.createDefaultTransport(properties,
					host -> new InetAddress[]{LOOPBACK_IP}, address -> false);
			HttpTransport rewriting = rewriteToLocalHost(server, "pinned.example", transport);
			ExternalImageFetcher fetcher = new ExternalImageFetcher(properties,
					new HostMirrorCircuitBreaker(properties), host -> new InetAddress[]{PUBLIC_IP}, rewriting,
					Clock.systemUTC());

			FetchResult result = fetcher.fetch("https://pinned.example/a.png", "h-pinned", null);

			assertThat(result.success()).isTrue();
			assertThat(serverHits.get()).isEqualTo(1);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void 다운로드캐시에바이트상한이있을때_큰응답을연속저장하면_총바이트가상한을넘지않는다() {
		MutableClock clock = new MutableClock(Instant.parse("2026-11-21T00:00:00Z"));
		StorageProperties properties = StorageProperties.defaultsForMemory().withDedup(30L, 2500L);
		ExternalImageFetcher fetcher = new ExternalImageFetcher(properties, new HostMirrorCircuitBreaker(properties),
				host -> new InetAddress[]{PUBLIC_IP}, (uri, timeout) -> new HttpResponseData(200, "image/png", 1000,
						null, new ByteArrayInputStream(new byte[1000])),
				clock);

		for (int i = 0; i < 10; i++) {
			assertThat(fetcher.fetch("https://image.msscdn.net/" + i + ".png", "cap-" + i, "무신사").success()).isTrue();
			assertThat(fetcher.cachedBytes()).isLessThanOrEqualTo(2500L);
		}
		assertThat(fetcher.cachedEntryCount()).isEqualTo(2);
	}

	@Test
	void 만료된캐시항목이있을때_다른URL을저장하면_조회없이도만료항목을제거한다() {
		MutableClock clock = new MutableClock(Instant.parse("2026-11-21T00:00:00Z"));
		StorageProperties properties = StorageProperties.defaultsForMemory().withDedup(30L, 1_000_000L);
		ExternalImageFetcher fetcher = new ExternalImageFetcher(properties, new HostMirrorCircuitBreaker(properties),
				host -> new InetAddress[]{PUBLIC_IP}, (uri, timeout) -> new HttpResponseData(200, "image/png", 100,
						null, new ByteArrayInputStream(new byte[100])),
				clock);

		fetcher.fetch("https://image.msscdn.net/a.png", "exp-a", "무신사");
		fetcher.fetch("https://image.msscdn.net/b.png", "exp-b", "무신사");
		assertThat(fetcher.cachedEntryCount()).isEqualTo(2);

		clock.advance(Duration.ofSeconds(31));
		fetcher.fetch("https://image.msscdn.net/c.png", "exp-c", "무신사");

		assertThat(fetcher.cachedEntryCount()).isEqualTo(1);
		assertThat(fetcher.cachedBytes()).isEqualTo(100L);
	}

	private static StorageProperties propertiesWithReadTimeout(int readTimeoutMs) {
		return new StorageProperties("memory", "", "", "product-images", 900L,
				StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES, true, List.of(), false, 5000L, 5, 5000, readTimeoutMs, 2,
				0L, 3, 300L, 30L, null, null, null, null, null, null, null);
	}

	private static HttpTransport rewriteToLocal(HttpServer server, HttpTransport real) {
		return (uri, timeout) -> real
				.get(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + uri.getRawPath()), timeout);
	}

	private static HttpTransport rewriteToLocalHost(HttpServer server, String host, HttpTransport real) {
		return (uri, timeout) -> real
				.get(URI.create("http://" + host + ":" + server.getAddress().getPort() + uri.getRawPath()), timeout);
	}

	private static InetAddress mustParse(String literal) {
		try {
			return InetAddress.getByName(literal);
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static final class MutableClock extends Clock {
		private final AtomicReference<Instant> current;

		private MutableClock(Instant initial) {
			this.current = new AtomicReference<>(initial);
		}

		private void advance(Duration duration) {
			current.updateAndGet(now -> now.plus(duration));
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return current.get();
		}
	}
}
