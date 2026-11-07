package com.ject.ziper.global.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.ject.ziper.global.observability.MonitoringIntegrationTest.RecordingTransport;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.p6spy.engine.spy.P6SpyDriver;
import com.zaxxer.hikari.HikariDataSource;

import io.sentry.Sentry;
import io.sentry.SpanStatus;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {"sentry.enabled=true",
		"sentry.dsn=https://public@example.com/1", "sentry.traces-sample-rate=1"})
@ActiveProfiles("test")
@DirtiesContext
@Import({MonitoringIntegrationTest.Recording.class, TracingFixtures.Configuration.class})
class TracingIntegrationTest extends PostgresTestSupport {
	@Autowired
	TestRestTemplate http;
	@Autowired
	RecordingTransport transport;
	@Autowired
	DataSource dataSource;
	@Autowired
	Flyway flyway;

	@Test
	void 실제서비스와Postgres_하나의요청을추적하면_하위구간과반복쿼리시간을연결하고민감값은제거한다() throws Exception {
		transport.transactions.clear();
		var headers = new HttpHeaders();
		headers.setBearerAuth(MonitoringIntegrationTest.token());
		String traceId = "c55fd9edcc7b459f998ad79eac170d89";
		headers.set("sentry-trace", traceId + "-2d5f2e762b31403f-1");
		var response = http.exchange("/tracing-fixture/work?password=" + TracingFixtures.PRIVATE_VALUE, HttpMethod.GET,
				new HttpEntity<>(headers), Map.class);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getBody()).containsEntry("traceId", traceId);
		Sentry.flush(5000);
		var transaction = transport.transactions.stream()
				.filter(value -> value.getTransaction().equals("GET /tracing-fixture/work")).findFirst().orElseThrow();
		assertThat(transaction.getContexts().getTrace().getTraceId().toString()).isEqualTo(traceId);
		var service = transaction.getSpans().stream().filter(span -> span.getOp().equals("service")).findFirst()
				.orElseThrow();
		var repositories = transaction.getSpans().stream().filter(span -> span.getOp().equals("repository")).toList();
		var queries = transaction.getSpans().stream().filter(span -> span.getOp().equals("db.query")).toList();
		assertThat(repositories).hasSize(2);
		assertThat(queries).hasSize(2);
		assertThat(repositories).allSatisfy(span -> assertThat(span.getParentSpanId()).isEqualTo(service.getSpanId()));
		assertThat(queries).allSatisfy(span -> {
			assertThat(span.getParentSpanId()).isIn(repositories.stream().map(value -> value.getSpanId()).toList());
			assertThat(span.getTimestamp() - span.getStartTimestamp()).isGreaterThanOrEqualTo(0.04);
			assertThat(span.getDescription()).matches("JDBC [a-f0-9]{16}");
		});
		assertThat(queries.getFirst().getDescription()).isEqualTo(queries.getLast().getDescription());
		assertThat(transaction.getSpans().stream().filter(span -> span.getOp().equals("function"))).singleElement()
				.satisfies(span -> assertThat(span.getParentSpanId()).isEqualTo(service.getSpanId()));
		assertThat(transaction.getRequest().getHeaders()).isNull();
		assertThat(transaction.getRequest().getQueryString()).isNull();
		var encoded = new StringWriter();
		Sentry.getCurrentScopes().getOptions().getSerializer().serialize(transaction, encoded);
		assertThat(encoded.toString()).doesNotContain(TracingFixtures.PRIVATE_VALUE, "pg_sleep", "SELECT",
				RUNTIME_PASSWORD, "Bearer");
		assertThat(dataSource).isInstanceOf(HikariDataSource.class);
		assertThat(((HikariDataSource) dataSource).getDriverClassName()).isEqualTo(P6SpyDriver.class.getName());
		assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(5);
		try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
			assertThat(connection.getMetaData().getUserName()).isEqualTo("ziper_migrator");
		}
	}

	@Test
	void SQL실패요청_계측된경로를실행하면_공개오류계약을유지하고실패한쿼리스팬을남긴다() {
		transport.transactions.clear();
		var headers = new HttpHeaders();
		headers.setBearerAuth(MonitoringIntegrationTest.token());
		var response = http.exchange("/tracing-fixture/failure", HttpMethod.GET, new HttpEntity<>(headers),
				String.class);
		assertThat(response.getStatusCode().value()).isEqualTo(500);
		assertThat(response.getBody()).doesNotContain("SELECT", "tracing_missing_fixture");
		Sentry.flush(5000);
		var transaction = transport.transactions.stream()
				.filter(value -> value.getTransaction().equals("GET /tracing-fixture/failure")).findFirst()
				.orElseThrow();
		assertThat(transaction.getSpans().stream().filter(span -> span.getOp().equals("db.query"))).singleElement()
				.satisfies(span -> assertThat(span.getStatus()).isEqualTo(SpanStatus.INTERNAL_ERROR));
	}

	@Test
	void 프로브와추적헤더를받은서버_프로브와CORS를검사하면_프로브는제외하고허용된출처의추적헤더만통과시킨다() {
		transport.transactions.clear();
		assertThat(http.getForEntity("/readyz", String.class).getStatusCode().value()).isEqualTo(200);
		Sentry.flush(5000);
		assertThat(transport.transactions).isEmpty();
		var headers = new HttpHeaders();
		headers.setOrigin("http://localhost:3000");
		headers.setAccessControlRequestMethod(HttpMethod.GET);
		headers.setAccessControlRequestHeaders(List.of("sentry-trace", "baggage"));
		var response = http.exchange("/tracing-fixture/work", HttpMethod.OPTIONS, new HttpEntity<>(headers),
				String.class);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getHeaders().getAccessControlAllowHeaders()).contains("sentry-trace", "baggage");
		headers.setOrigin("https://untrusted.example");
		assertThat(http.exchange("/tracing-fixture/work", HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class)
				.getStatusCode().value()).isEqualTo(403);
	}
}
