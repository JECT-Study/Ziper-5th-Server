package com.ject.ziper.global.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.never;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.sentry.Sentry;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import com.ject.ziper.global.persistence.PostgresTestSupport;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(ProbeIntegrationTest.ErrorEndpoints.class)
class ProbeIntegrationTest extends PostgresTestSupport {
	@Autowired
	TestRestTemplate http;

	@Autowired
	MockMvc mvc;

	@Test
	void 인증된사용자_actuator와오류경로를요청하면_actuator는거부하고5xx만Sentry에수집한다() throws Exception {
		mvc.perform(get("/actuator/env").with(jwt())).andExpect(status().isForbidden());
		mvc.perform(get("/actuator/health").with(jwt())).andExpect(status().isForbidden());
		try (var sentry = mockStatic(Sentry.class, CALLS_REAL_METHODS)) {
			mvc.perform(get("/test/client-error").with(jwt())).andExpect(status().isBadRequest());
			sentry.verify(() -> Sentry.captureException(any(Exception.class)), never());
			mvc.perform(get("/test/server-error").with(jwt())).andExpect(status().isInternalServerError())
					.andExpect(jsonPath("detail").value("서버 오류가 발생했습니다."));
			sentry.verify(() -> Sentry.captureException(any(Exception.class)));
		}
	}

	@RestController
	static class ErrorEndpoints {
		@GetMapping("/test/client-error")
		void clientError() {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret-token");
		}

		@GetMapping("/test/server-error")
		void serverError() {
			throw new IllegalStateException("secret-token");
		}
	}

	@Test
	void 정상동작중인서버_프로브를요청하면_상태만노출하고캐시를금지한다() {
		for (String path : List.of("/livez", "/readyz", "/actuator/health/liveness", "/actuator/health/readiness")) {
			var response = http.getForEntity(path, Map.class);
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getHeaders().getCacheControl()).contains("no-store");
			assertThat(response.getHeaders().getFirst("X-Ziper-Origin")).isEqualTo("local");
			assertThat(response.getHeaders().getFirst("X-Ziper-Release")).isEqualTo("local");
			assertThat(response.getBody()).isEqualTo(Map.of("status", "UP"));
		}
	}

	@Test
	void DB를일시중단한상태_프로브를요청하고DB를복구하면_중단중에는readiness만DOWN이고복구후UP으로돌아온다() throws Exception {
		var docker = POSTGRES.getDockerClient();
		docker.pauseContainerCmd(POSTGRES.getContainerId()).exec();
		try {
			var readiness = http.getForEntity("/readyz", Map.class);
			assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(readiness.getHeaders().getCacheControl()).contains("no-store");
			assertThat(readiness.getBody()).isEqualTo(Map.of("status", "DOWN"));
			assertThat(http.getForEntity("/livez", Map.class).getBody()).isEqualTo(Map.of("status", "UP"));
		} finally {
			docker.unpauseContainerCmd(POSTGRES.getContainerId()).exec();
		}
		long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
		while (http.getForEntity("/readyz", Map.class).getStatusCode() != HttpStatus.OK
				&& System.nanoTime() < deadline) {
			Thread.sleep(200);
		}
		assertThat(http.getForEntity("/readyz", Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
	}
}
