package com.ject.ziper.domain.product;

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
		"app.runtime-mode=local", "app.security.jwt-secret-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE="})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class ProductExtractionOpenApiTest extends PostgresTestSupport {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper mapper;

	@Test
	void 로컬문서활성화환경에서_OpenAPI문서를조회하면_추출규칙과URL확인API가인증필요로노출된다() throws Exception {
		String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		JsonNode root = mapper.readTree(body);
		JsonNode paths = root.path("paths");

		JsonNode rulesOperation = paths.path("/api/v1/products/extraction-rules").path("get");
		assertThat(rulesOperation.isMissingNode()).isFalse();
		assertThat(rulesOperation.path("summary").asText()).isNotBlank();
		assertThat(rulesOperation.has("security")).isFalse();

		JsonNode urlCheckOperation = paths.path("/api/v1/products/url-check").path("post");
		assertThat(urlCheckOperation.isMissingNode()).isFalse();
		assertThat(urlCheckOperation.path("summary").asText()).isNotBlank();
		assertThat(urlCheckOperation.has("security")).isFalse();

		assertThat(root.path("security").isArray()).isTrue();
		assertThat(root.path("security").get(0).has("bearerJWT")).isTrue();
	}
}
