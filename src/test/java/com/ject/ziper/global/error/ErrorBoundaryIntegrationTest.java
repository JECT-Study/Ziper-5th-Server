package com.ject.ziper.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.global.observability.RequestIdFilter;
import com.ject.ziper.global.observability.SentryConfiguration;
import com.ject.ziper.global.security.SecurityConfiguration;
import com.ject.ziper.global.config.OpenApiConfiguration;

import io.sentry.Hint;
import io.sentry.ITransportFactory;
import io.sentry.Sentry;
import io.sentry.SentryEnvelope;
import io.sentry.SentryEvent;
import io.sentry.transport.ITransport;
import io.sentry.transport.RateLimiter;

@SpringBootTest(classes = ErrorBoundaryIntegrationTest.Fixture.class, properties = {
		"app.security.jwt-secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "spring.flyway.enabled=false",
		"management.endpoint.health.group.readiness.include=readinessState", "sentry.enabled=true",
		"sentry.dsn=https://public@example.com/1", "sentry.traces-sample-rate=0"})
@AutoConfigureMockMvc
class ErrorBoundaryIntegrationTest {
	@Autowired
	MockMvc mvc;
	@Autowired
	RecordingTransport transport;

	@Test
	void 업무오류와예상하지못한실패_Spring과Sentry로처리하면_5xx만한번수집하고서버생성요청ID로연결한다() throws Exception {
		transport.events.clear();
		mvc.perform(get("/business").with(jwt())).andExpect(status().isConflict())
				.andExpect(jsonPath("code").value("CONFLICT"));
		assertThat(transport.events).isEmpty();
		var response = mvc.perform(get("/failure").with(jwt()).header("X-Request-Id", "attacker"))
				.andExpect(status().isInternalServerError()).andExpect(jsonPath("code").value("INTERNAL_ERROR"))
				.andReturn().getResponse();
		String id = response.getHeader("X-Request-Id");
		assertThat(id).isNotBlank().isNotEqualTo("attacker");
		assertThat(response.getContentAsString()).contains(id).doesNotContain("secret", "IllegalStateException");
		Sentry.flush(2000);
		assertThat(transport.events).hasSize(1);
		assertThat(transport.events.getFirst().getTag("requestId")).isEqualTo(id);
		assertThat(MDC.get("requestId")).isNull();
		var next = mvc.perform(get("/private")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("code").value("UNAUTHORIZED")).andReturn().getResponse();
		assertThat(next.getHeader("X-Request-Id")).isNotEqualTo(id);
		assertThat(transport.events).hasSize(1);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
	@Import({SecurityConfiguration.class, OpenApiConfiguration.class, ProblemFactory.class, ApiExceptionHandler.class,
			SafeErrorController.class, RequestIdFilter.class, SentryConfiguration.class, Endpoints.class})
	static class Fixture {
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
		final List<SentryEvent> events = new CopyOnWriteArrayList<>();
		@Override
		public void send(SentryEnvelope envelope, Hint hint) {
			for (var item : envelope.getItems()) {
				try {
					var event = item.getEvent(Sentry.getCurrentScopes().getOptions().getSerializer());
					if (event != null) {
						events.add(event);
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
		public void close(boolean restart) {
		}
	}

	@RestController
	static class Endpoints {
		@GetMapping("/business")
		void business() {
			throw new BusinessException(ErrorCode.CONFLICT);
		}
		@GetMapping("/failure")
		void failure() {
			throw new IllegalStateException("secret-token SQL");
		}
	}
}
