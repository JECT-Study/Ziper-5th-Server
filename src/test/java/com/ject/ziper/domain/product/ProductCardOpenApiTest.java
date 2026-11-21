package com.ject.ziper.domain.product;

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
class ProductCardOpenApiTest extends PostgresTestSupport {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper mapper;

	@Test
	void 로컬문서활성화상태에서_스펙을조회하면_상품카드신규등록API가한글문서와JWT인증으로노출된다() throws Exception {
		String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		JsonNode root = mapper.readTree(body);

		JsonNode createOperation = root.path("paths").path("/api/v1/products").path("post");
		assertThat(createOperation.isMissingNode()).isFalse();
		assertThat(createOperation.path("summary").asText()).contains("상품 카드 신규 등록");
		assertThat(createOperation.path("description").asText()).isNotBlank();
		assertThat(createOperation.has("security")).isFalse();
		assertThat(createOperation.path("requestBody").path("required").asBoolean()).isTrue();

		assertThat(root.path("security").isArray()).isTrue();
		assertThat(root.path("security").toString()).contains("bearerJWT");
	}
}
