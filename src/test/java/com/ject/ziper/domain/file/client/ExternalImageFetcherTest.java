package com.ject.ziper.domain.file.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
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
import com.ject.ziper.domain.file.client.ExternalImageFetcher.HttpResponseData;
import com.ject.ziper.global.storage.StorageProperties;

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
				List.of(), false, 5000L, 5, 5000, 10000, 2, 0L, 3, 300L, 30L);
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
	void 호스트에서403또는429연속3회발생_이미지다운로드를요청하면_서킷브레이커가차단하고쿨다운후복구된다() {
		MutableClock clock = new MutableClock(Instant.parse("2026-11-21T00:00:00Z"));
		StorageProperties properties = new StorageProperties("memory", "", "", "product-images", 900L,
				StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES, true, List.of(), false, 5000L, 5, 5000, 10000, 2, 0L, 3,
				60L, 0L);
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
		responseStatus.set(403);
		assertThat(fetcher.fetch("https://cf.product-image.s.zigzag.kr/2.jpg", "h2", "지그재그").errorReason())
				.isEqualTo("HTTP_403");
		assertThat(fetcher.fetch("https://cf.product-image.s.zigzag.kr/3.jpg", "h3", "지그재그").errorReason())
				.isEqualTo("HTTP_403");
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
