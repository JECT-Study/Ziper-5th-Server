package com.ject.ziper.global.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

import com.ject.ziper.global.persistence.PostgresTestSupport;

import io.sentry.Sentry;
import io.sentry.SentryOptions;

@EnabledIfEnvironmentVariable(named = "SENTRY_LIVE_VERIFICATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"sentry.enabled=true",
		"sentry.dsn=${SENTRY_DSN}", "sentry.environment=local-monitoring",
		"sentry.release=${SENTRY_RELEASE:local-monitoring}", "sentry.traces-sample-rate=1",
		"app.monitoring.metrics-enabled=true", "app.monitoring.origin=local"})
@ActiveProfiles("test")
@Import({MonitoringIntegrationTest.Endpoints.class, SentryLiveVerificationTest.Verification.class})
class SentryLiveVerificationTest extends PostgresTestSupport {
	private static final String RUN_ID = UUID.randomUUID().toString();
	@Autowired
	TestRestTemplate http;
	@Autowired
	ResourceMetricsCollector collector;

	@Test
	void 명시적으로선택한외부Sentry_실제HTTP와자원을전송하면_오류와트레이스와지표를검증할수있다() {
		assertThat(System.getenv("SENTRY_DSN")).as("SENTRY_DSN must be supplied privately").isNotBlank();
		var headers = new HttpHeaders();
		headers.setBearerAuth(MonitoringIntegrationTest.token());
		for (String endpoint : new String[]{"ok", "business", "failure"}) {
			int expected = endpoint.equals("ok") ? 200 : endpoint.equals("business") ? 409 : 500;
			assertThat(http.exchange("/monitoring-fixture/" + endpoint, HttpMethod.GET, new HttpEntity<>(headers),
					String.class).getStatusCode().value()).isEqualTo(expected);
		}
		assertThat(http.getForEntity("/monitoring-fixture/ok", String.class).getStatusCode().value()).isEqualTo(401);
		collector.collect();
		collector.collect();
		Sentry.flush(10000);
		System.out.println("Sentry verification run=" + RUN_ID);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Verification {
		@Bean
		@Primary
		SentryOptions.BeforeSendCallback verificationPrivacy() {
			var privacy = new SentryConfiguration().privacyBeforeSend();
			return (event, hint) -> {
				var safe = privacy.execute(event, hint);
				if (safe != null) {
					safe.setFingerprints(List.of("ziper-monitoring-verification", RUN_ID));
					safe.setTag("verificationRun", RUN_ID);
				}
				return safe;
			};
		}
	}
}
