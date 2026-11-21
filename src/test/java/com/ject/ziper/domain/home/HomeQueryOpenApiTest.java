package com.ject.ziper.domain.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

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
		"app.runtime-mode=local", "app.security.jwt-secret-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class HomeQueryOpenApiTest extends PostgresTestSupport {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper mapper;

	@Test
	void 로컬문서활성화상태에서_홈요약스펙을조회하면_한글문서와파라미터와JWT인증으로노출된다() throws Exception {
		String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		JsonNode root = mapper.readTree(body);

		JsonNode homeOp = root.path("paths").path("/api/v1/home").path("get");
		assertThat(homeOp.isMissingNode()).isFalse();
		assertThat(homeOp.path("summary").asText()).contains("홈 화면 요약 조회");
		assertThat(homeOp.path("description").asText()).isNotBlank();
		assertThat(homeOp.path("tags").toString()).contains("홈");
		assertThat(homeOp.has("security")).isFalse();
		assertThat(homeOp.path("parameters").toString()).contains("recentLimit");

		JsonNode schemas = root.path("components").path("schemas");
		assertThat(schemas.has("HomeSummaryResponse")).isTrue();
		assertThat(schemas.has("HomeBoardSummaryResponse")).isTrue();
		assertThat(schemas.has("HomePreviewImageResponse")).isTrue();
		assertThat(schemas.has("HomeRecentProductResponse")).isTrue();

		assertThat(root.path("security").isArray()).isTrue();
		assertThat(root.path("security").toString()).contains("bearerJWT");
	}
}
