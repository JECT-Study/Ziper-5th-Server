package com.ject.ziper.global.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.ject.ziper.global.persistence.PostgresTestSupport;

import io.sentry.Sentry;

@EnabledIfEnvironmentVariable(named = "SENTRY_PERFORMANCE_LIVE_VERIFICATION", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"sentry.enabled=true",
		"sentry.dsn=${SENTRY_DSN}", "sentry.environment=local-performance",
		"sentry.release=${SENTRY_RELEASE:local-performance}", "sentry.traces-sample-rate=1",
		"sentry.profile-session-sample-rate=${SENTRY_PROFILE_SESSION_SAMPLE_RATE:1.0}",
		"sentry.tags.verificationRun=${SENTRY_VERIFICATION_RUN:local-performance}"})
@ActiveProfiles("test")
@DirtiesContext
@Import(TracingFixtures.Configuration.class)
class SentryTracingLiveVerificationTest extends PostgresTestSupport {
	@Autowired
	TestRestTemplate http;

	@Test
	void 명시적으로선택한외부Sentry_실제HTTP와서비스및SQL을실행하면_동일트레이스를검증할수있다() {
		assertThat(System.getenv("SENTRY_DSN")).as("SENTRY_DSN must be supplied privately").isNotBlank();
		var headers = new HttpHeaders();
		headers.setBearerAuth(MonitoringIntegrationTest.token());
		var response = http.exchange("/tracing-fixture/work", HttpMethod.GET, new HttpEntity<>(headers), Map.class);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody().get("traceId").toString()).matches("[a-f0-9]{32}");
		if (Sentry.getCurrentScopes().getOptions().getProfileSessionSampleRate() > 0) {
			assertThat(response.getBody()).containsEntry("profilerRunning", "true");
			assertThat(response.getBody().get("profilerId").toString())
					.isNotEqualTo("00000000000000000000000000000000");
		}
		Sentry.flush(10000);
		System.out.println("Sentry performance trace=" + response.getBody().get("traceId"));
		System.out.println("Sentry performance profiler=" + response.getBody().get("profilerId"));
	}
}
