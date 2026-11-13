package com.ject.ziper.global.observability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.sentry.Sentry;
import io.sentry.spring.jakarta.tracing.SentrySpan;

final class TracingFixtures {
	static final String PRIVATE_VALUE = "tracing-private-value";

	private TracingFixtures() {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class Configuration {
		@Bean
		Endpoints tracingEndpoints(WorkService work, QueryRepository repository) {
			return new Endpoints(work, repository);
		}

		@Bean
		WorkService tracingWorkService(QueryRepository repository, ComputeService compute) {
			return new WorkService(repository, compute);
		}

		@Bean
		QueryRepository tracingQueryRepository(JdbcTemplate jdbc) {
			return new QueryRepository(jdbc);
		}

		@Bean
		ComputeService tracingComputeService() {
			return new ComputeService();
		}
	}

	@RestController
	@TestComponent
	static class Endpoints {
		private final WorkService work;
		private final QueryRepository repository;

		Endpoints(WorkService work, QueryRepository repository) {
			this.work = work;
			this.repository = repository;
		}

		@GetMapping("/tracing-fixture/work")
		Map<String, String> work() throws NoSuchAlgorithmException {
			String result = work.process(PRIVATE_VALUE);
			var profiler = Sentry.getCurrentScopes().getOptions().getContinuousProfiler();
			return Map.of("result", result, "traceId", Sentry.getSpan().getSpanContext().getTraceId().toString(),
					"profilerRunning", Boolean.toString(profiler.isRunning()), "profilerId",
					profiler.getProfilerId().toString());
		}

		@GetMapping("/tracing-fixture/failure")
		void failure() {
			repository.fail();
		}
	}

	static class WorkService {
		private final QueryRepository repository;
		private final ComputeService compute;

		WorkService(QueryRepository repository, ComputeService compute) {
			this.repository = repository;
			this.compute = compute;
		}

		@SentrySpan(operation = "service", description = "TracingFixture.process")
		public String process(String value) throws NoSuchAlgorithmException {
			String first = repository.lookup(value);
			String second = repository.lookup(value + "-second");
			return compute.digest(first + second);
		}
	}

	static class QueryRepository {
		private final JdbcTemplate jdbc;

		QueryRepository(JdbcTemplate jdbc) {
			this.jdbc = jdbc;
		}

		@SentrySpan(operation = "repository", description = "TracingFixture.lookup")
		public String lookup(String value) {
			return jdbc.queryForObject("SELECT ?::text FROM pg_sleep(?)", String.class, value, 0.05);
		}

		@SentrySpan(operation = "repository", description = "TracingFixture.failure")
		public void fail() {
			jdbc.execute("SELECT * FROM ziper.tracing_missing_fixture");
		}
	}

	static class ComputeService {
		@SentrySpan(operation = "function", description = "TracingFixture.digest")
		public String digest(String value) throws NoSuchAlgorithmException {
			var hash = MessageDigest.getInstance("SHA-256");
			byte[] result = value.getBytes(StandardCharsets.UTF_8);
			for (int index = 0; index < 100_000; index++) {
				result = hash.digest(result);
			}
			return HexFormat.of().formatHex(result);
		}
	}
}
