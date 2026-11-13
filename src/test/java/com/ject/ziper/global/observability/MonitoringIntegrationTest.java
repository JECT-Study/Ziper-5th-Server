package com.ject.ziper.global.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.persistence.PostgresTestSupport;

import io.sentry.Hint;
import io.sentry.ITransportFactory;
import io.sentry.Sentry;
import io.sentry.SentryEnvelope;
import io.sentry.SentryEvent;
import io.sentry.SentryMetricsEvent;
import io.sentry.protocol.SentryTransaction;
import io.sentry.transport.ITransport;
import io.sentry.transport.RateLimiter;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"sentry.enabled=true",
		"sentry.dsn=https://public@example.com/1", "sentry.traces-sample-rate=0",
		"app.monitoring.metrics-enabled=true"})
@ActiveProfiles("test")
@Import({MonitoringIntegrationTest.Recording.class, MonitoringIntegrationTest.Endpoints.class})
class MonitoringIntegrationTest extends PostgresTestSupport {
	@Autowired
	TestRestTemplate http;
	@Autowired
	RecordingTransport transport;
	@Autowired
	ResourceMetricsCollector collector;

	@Test
	void 실제HTTP와Postgres_요청과자원을수집하면_샘플링없이상태별지표와오류한건을전송한다() {
		transport.metrics.clear();
		transport.events.clear();
		assertThat(request("/monitoring-fixture/ok", true)).isEqualTo(200);
		assertThat(request("/monitoring-fixture/ok", false)).isEqualTo(401);
		assertThat(request("/monitoring-fixture/business", true)).isEqualTo(409);
		assertThat(request("/monitoring-fixture/failure", true)).isEqualTo(500);
		assertThat(request("/livez", false)).isEqualTo(200);
		assertThat(request("/readyz", false)).isEqualTo(200);
		request("/actuator/env", true);
		request("/swagger-ui.html", true);
		collector.collect();
		Sentry.flush(5000);
		var requests = transport.metrics.stream().filter(metric -> metric.getName().equals("ziper.http.requests"))
				.toList();
		assertThat(requests).hasSize(4);
		assertThat(requests).extracting(metric -> metric.getAttributes().get("status").getValue())
				.containsExactlyInAnyOrder("200", "401", "409", "500");
		assertThat(transport.metrics.stream().filter(metric -> metric.getName().equals("ziper.http.duration")))
				.hasSize(4);
		assertThat(transport.metrics).extracting(SentryMetricsEvent::getName).contains("ziper.jvm.heap.used",
				"ziper.jvm.heap.max", "ziper.db.active", "ziper.db.max");
		assertThat(transport.metrics).allSatisfy(
				metric -> assertThat(metric.getAttributes().keySet()).isSubsetOf(MonitoringPublisher.ATTRIBUTES));
		assertThat(transport.metrics.stream().filter(metric -> metric.getName().equals("ziper.http.failure")))
				.extracting(SentryMetricsEvent::getValue).containsExactlyInAnyOrder(0.0, 0.0, 0.0, 1.0);
		assertThat(transport.events).hasSize(1);
	}

	private int request(String path, boolean authenticated) {
		var headers = new HttpHeaders();
		if (authenticated) {
			headers.setBearerAuth(token());
		}
		return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode().value();
	}

	static String token() {
		var encoder = new NimbusJwtEncoder(
				new ImmutableSecret<>(Base64.getDecoder().decode("MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")));
		var claims = JwtClaimsSet.builder().issuer("ziper-server").subject("verification")
				.audience(List.of("ziper-api")).expiresAt(Instant.now().plusSeconds(60)).build();
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Recording {
		@Bean
		RecordingTransport transport() {
			return new RecordingTransport();
		}
		@Bean
		ITransportFactory transportFactory(RecordingTransport transport) {
			return (options, details) -> transport;
		}
	}

	static class RecordingTransport implements ITransport {
		final List<SentryMetricsEvent> metrics = new CopyOnWriteArrayList<>();
		final List<SentryEvent> events = new CopyOnWriteArrayList<>();
		final List<SentryTransaction> transactions = new CopyOnWriteArrayList<>();
		@Override
		public void send(SentryEnvelope envelope, Hint hint) {
			for (var item : envelope.getItems()) {
				try {
					var serializer = Sentry.getCurrentScopes().getOptions().getSerializer();
					var batch = item.getMetrics(serializer);
					if (batch != null) {
						metrics.addAll(batch.getItems());
					}
					var event = item.getEvent(serializer);
					if (event != null) {
						events.add(event);
					}
					var transaction = item.getTransaction(serializer);
					if (transaction != null) {
						transactions.add(transaction);
					}
				} catch (Exception exception) {
					throw new IllegalStateException(exception);
				}
			}
		}
		@Override
		public void flush(long timeoutMillis) {
		}
		@Override
		public RateLimiter getRateLimiter() {
			return null;
		}
		@Override
		public void close() {
		}
		@Override
		public void close(boolean restarting) {
		}
	}

	@RestController
	static class Endpoints {
		@GetMapping("/monitoring-fixture/ok")
		String ok() {
			return "ok";
		}
		@GetMapping("/monitoring-fixture/business")
		void business() {
			throw new BusinessException(ErrorCode.CONFLICT);
		}
		@GetMapping("/monitoring-fixture/failure")
		void failure() {
			throw new IllegalStateException("Monitoring verification");
		}
	}
}
