package com.ject.ziper.domain.file.client;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import com.ject.ziper.global.storage.StorageProperties;

/**
 * Apache HttpClient 5 transport for external image downloads: no redirects, no
 * retries, no cookies, no content decoding, connections only to addresses
 * vetted by {@link PinnedDnsResolver}, and a total deadline for the
 * request/response header phase (the JDK client only bounds that phase as well,
 * and per-read socket timeouts alone are reset by every trickled byte).
 */
final class ApacheImageHttpTransport implements ExternalImageFetcher.HttpTransport {
	private static final ScheduledExecutorService WATCHDOG = createWatchdog();

	private final CloseableHttpClient client;

	private ApacheImageHttpTransport(CloseableHttpClient client) {
		this.client = client;
	}

	static ApacheImageHttpTransport create(StorageProperties properties, ExternalImageFetcher.DnsResolver dnsResolver,
			Predicate<InetAddress> forbidden) {
		ConnectionConfig connectionConfig = ConnectionConfig.custom()
				.setConnectTimeout(Timeout.ofMilliseconds(properties.mirrorConnectTimeoutMs()))
				.setSocketTimeout(Timeout.ofMilliseconds(properties.mirrorReadTimeoutMs()))
				.setTimeToLive(TimeValue.ofMinutes(1)).build();
		PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
				.setDnsResolver(new PinnedDnsResolver(dnsResolver, forbidden))
				.setDefaultConnectionConfig(connectionConfig).setMaxConnTotal(20).setMaxConnPerRoute(4).build();
		RequestConfig requestConfig = RequestConfig.custom()
				.setResponseTimeout(Timeout.ofMilliseconds(properties.mirrorReadTimeoutMs())).build();
		CloseableHttpClient client = HttpClients.custom().setConnectionManager(connectionManager)
				.setDefaultRequestConfig(requestConfig).disableRedirectHandling().disableAutomaticRetries()
				.disableCookieManagement().disableContentCompression().disableAuthCaching().build();
		return new ApacheImageHttpTransport(client);
	}

	static ScheduledFuture<?> schedule(Runnable task, Duration delay) {
		return WATCHDOG.schedule(task, Math.max(1L, delay.toNanos()), TimeUnit.NANOSECONDS);
	}

	@Override
	public ExternalImageFetcher.HttpResponseData get(URI uri, Duration timeout) throws IOException {
		HttpGet request = new HttpGet(uri);
		request.addHeader("Accept", "image/webp,image/png,image/jpeg,image/*;q=0.8");
		AtomicBoolean deadlineHit = new AtomicBoolean(false);
		ScheduledFuture<?> guard = schedule(() -> {
			deadlineHit.set(true);
			request.abort();
		}, timeout);
		ClassicHttpResponse response;
		try {
			response = client.executeOpen(null, request, HttpClientContext.create());
		} catch (IOException exception) {
			if (deadlineHit.get()) {
				throw new SocketTimeoutException("Image request exceeded its deadline");
			}
			throw exception;
		} finally {
			guard.cancel(false);
		}
		HttpEntity entity = response.getEntity();
		InputStream content = entity == null ? InputStream.nullInputStream() : entity.getContent();
		long contentLength = entity == null ? -1L : entity.getContentLength();
		return new ExternalImageFetcher.HttpResponseData(response.getCode(), headerValue(response, "Content-Type"),
				contentLength, headerValue(response, "Location"), new ClosingInputStream(content, response),
				request::abort);
	}

	private static String headerValue(ClassicHttpResponse response, String name) {
		Header header = response.getFirstHeader(name);
		return header == null ? null : header.getValue();
	}

	private static ScheduledExecutorService createWatchdog() {
		ScheduledThreadPoolExecutor executor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1,
				runnable -> {
					Thread thread = new Thread(runnable, "image-fetch-watchdog");
					thread.setDaemon(true);
					return thread;
				});
		executor.setRemoveOnCancelPolicy(true);
		return executor;
	}

	/**
	 * Closing the stream closes the response, which discards a connection that was
	 * not fully consumed.
	 */
	private static final class ClosingInputStream extends FilterInputStream {
		private final ClassicHttpResponse response;

		private ClosingInputStream(InputStream in, ClassicHttpResponse response) {
			super(in);
			this.response = response;
		}

		@Override
		public void close() throws IOException {
			response.close();
		}
	}
}
