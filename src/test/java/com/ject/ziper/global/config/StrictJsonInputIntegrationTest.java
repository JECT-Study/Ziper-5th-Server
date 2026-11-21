package com.ject.ziper.global.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.global.error.ApiExceptionHandler;
import com.ject.ziper.global.error.ProblemFactory;
import com.ject.ziper.global.error.SafeErrorController;
import com.ject.ziper.global.observability.RequestIdFilter;
import com.ject.ziper.global.security.SecurityConfiguration;

@SpringBootTest(classes = StrictJsonInputIntegrationTest.Fixture.class, properties = {
		"app.security.jwt-secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "spring.flyway.enabled=false",
		"management.endpoint.health.group.readiness.include=readinessState"})
@AutoConfigureMockMvc
class StrictJsonInputIntegrationTest {
	@Autowired
	MockMvc mvc;

	@ParameterizedTest
	@ValueSource(strings = {"\\u0000", "x\\u0000y", "\\u0007"})
	void 문자열필드에제어문자가주어졌을때_요청하면_400과공통요청오류를응답한다(String escaped) throws Exception {
		mvc.perform(post("/echo").with(jwt()).contentType("application/json")
				.content("{\"memo\":\"" + escaped + "\",\"price\":1}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("code").value("INVALID_REQUEST"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"29000.9", "\"29000\"", "true"})
	void 정수필드에강제변환대상이주어졌을때_요청하면_400과공통요청오류를응답한다(String value) throws Exception {
		mvc.perform(post("/echo").with(jwt()).contentType("application/json")
				.content("{\"memo\":\"ok\",\"price\":" + value + "}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("code").value("INVALID_REQUEST"));
	}

	@Test
	void 줄바꿈이담긴메모와정수가격_요청하면_그대로받아들인다() throws Exception {
		mvc.perform(post("/echo").with(jwt()).contentType("application/json")
				.content("{\"memo\":\"a\\nb\",\"price\":29000}")).andExpect(status().isOk())
				.andExpect(jsonPath("memo").value("a\nb")).andExpect(jsonPath("price").value(29000));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
	@Import({SecurityConfiguration.class, OpenApiConfiguration.class, ProblemFactory.class, ApiExceptionHandler.class,
			SafeErrorController.class, RequestIdFilter.class, JacksonConfiguration.class, Endpoints.class})
	static class Fixture {
	}

	record Body(String memo, Long price) {
	}

	@RestController
	static class Endpoints {
		@PostMapping("/echo")
		Body echo(@RequestBody Body body) {
			return body;
		}
	}
}
