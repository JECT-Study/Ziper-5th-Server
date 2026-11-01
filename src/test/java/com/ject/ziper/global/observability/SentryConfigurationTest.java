package com.ject.ziper.global.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

import io.sentry.CustomSamplingContext;
import io.sentry.Hint;
import io.sentry.SamplingContext;
import io.sentry.SentryEvent;
import io.sentry.TransactionContext;
import io.sentry.protocol.Request;
import io.sentry.protocol.User;

class SentryConfigurationTest {
	private final SentryConfiguration configuration = new SentryConfiguration();

	@Test
	void 민감한요청정보가담긴이벤트_전송전처리하면_사용자와요청민감필드를지우고URL은경로까지만남긴다() {
		var event = new SentryEvent(new IllegalStateException("failure"));
		event.setUser(new User());
		var request = new Request();
		request.setUrl("https://username:password@example.com/private?token=secret#secret");
		request.setQueryString("token=secret");
		request.setData(Map.of("password", "secret"));
		request.setHeaders(Map.of("Authorization", "Bearer secret"));
		request.setCookies("session=secret");
		request.setEnvs(Map.of("secret", "value"));
		request.setOthers(Map.of("secret", "value"));
		request.setUnknown(Map.of("secret", "value"));
		event.setRequest(request);
		assertThat(configuration.privacyBeforeSend().execute(event, new Hint())).isSameAs(event);
		assertThat(event.getUser()).isNull();
		assertThat(request.getUrl()).isEqualTo("https://example.com/private");
		assertThat(Stream.of(request.getQueryString(), request.getData(), request.getHeaders(), request.getCookies(),
				request.getEnvs(), request.getOthers(), request.getUnknown()).allMatch(value -> value == null))
				.isTrue();
	}

	@Test
	void 예상한클라이언트오류이벤트_전송전처리하면_전송하지않는다() {
		assertThat(configuration.privacyBeforeSend().execute(new SentryEvent(new BusinessException(ErrorCode.CONFLICT)),
				new Hint())).isNull();
		assertThat(configuration.privacyBeforeSend()
				.execute(new SentryEvent(new ResponseStatusException(HttpStatus.BAD_REQUEST, "secret")), new Hint()))
				.isNull();
	}

	@Test
	void 프로브와별칭경로및일반경로요청_트레이스를샘플링하면_프로브만제외하고일반경로는기본샘플링에맡긴다() {
		for (String path : new String[]{"/livez", "/readyz", "/actuator/health", "/actuator/health/liveness",
				"/actuator/health/readiness"}) {
			var custom = new CustomSamplingContext();
			custom.set("request", new MockHttpServletRequest("GET", path));
			assertThat(configuration.healthTracesSampler()
					.sample(new SamplingContext(new TransactionContext("unresolved", "http.server"), custom))).isZero();
			assertThat(configuration.healthTracesSampler()
					.sample(new SamplingContext(new TransactionContext("GET " + path, "http.server"), null))).isZero();
		}
		assertThat(configuration.healthTracesSampler()
				.sample(new SamplingContext(new TransactionContext("GET /private", "http.server"), null))).isNull();
	}
}
