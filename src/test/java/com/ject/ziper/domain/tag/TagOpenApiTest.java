package com.ject.ziper.domain.tag;

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
class TagOpenApiTest extends PostgresTestSupport {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper mapper;

	@Test
	void 로컬Swagger활성환경_OpenAPI문서를조회하면_해시태그CRUD경로가JWT인증스킴으로노출된다() throws Exception {
		String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		JsonNode root = mapper.readTree(body);
		JsonNode paths = root.path("paths");

		JsonNode listOp = paths.path("/api/v1/tags").path("get");
		JsonNode createOp = paths.path("/api/v1/tags").path("post");
		JsonNode deleteOp = paths.path("/api/v1/tags/{tagId}").path("delete");

		for (JsonNode op : new JsonNode[]{listOp, createOp, deleteOp}) {
			assertThat(op.isMissingNode()).isFalse();
			assertThat(op.path("summary").asText()).isNotBlank();
			assertThat(op.path("tags").get(0).asText()).isEqualTo("해시태그");
			assertThat(op.path("security").isMissingNode()).isTrue();
		}
		assertThat(root.path("security").toString()).contains("bearerJWT");
	}
}
