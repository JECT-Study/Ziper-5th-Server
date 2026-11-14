package com.ject.ziper.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.global.persistence.PostgresTestSupport;

@SpringBootTest(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true",
		"app.runtime-mode=local", "app.mail.enabled=true", "app.mail.from=developer@example.com",
		"app.security.jwt-secret-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
		"app.mail.code-secret-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=", "spring.mail.host=localhost"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class EmailVerificationOpenApiTest extends PostgresTestSupport {
	@Autowired
	private MockMvc mvc;
	@Autowired
	private ObjectMapper mapper;

	@Test
	void 로컬에서메일기능을켰을때_문서를요청하면_네경로를공개인증으로표시한다() throws Exception {
		String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		JsonNode paths = mapper.readTree(body).path("paths");
		for (String path : new String[]{"/api/v1/auth/email/verifications",
				"/api/v1/auth/email/verifications/{flowId}/resend",
				"/api/v1/auth/email/verifications/{flowId}/verify"}) {
			JsonNode operation = paths.path(path).path("post");
			assertThat(operation.isMissingNode()).isFalse();
			assertThat(operation.path("security").isArray()).isTrue();
			assertThat(operation.path("security").isEmpty()).isTrue();
		}
		JsonNode status = paths.path("/api/v1/auth/email/verifications/{flowId}").path("get");
		assertThat(status.isMissingNode()).isFalse();
		assertThat(status.path("security").isArray()).isTrue();
		assertThat(status.path("security").isEmpty()).isTrue();
	}
}
