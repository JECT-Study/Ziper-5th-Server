package com.ject.ziper.domain.board;

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
class ProductBoardOpenApiTest extends PostgresTestSupport {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper mapper;

	@Test
	void 로컬문서활성화상태에서_보드명령경로를조회하면_인증필요와한국어명세가포함된다() throws Exception {
		String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		JsonNode paths = mapper.readTree(body).path("paths");

		JsonNode createOp = paths.path("/api/v1/boards").path("post");
		assertThat(createOp.isMissingNode()).isFalse();
		assertThat(createOp.path("summary").asText()).isNotBlank();
		assertThat(createOp.path("tags").toString()).contains("보드");
		assertThat(createOp.has("security")).isFalse();

		JsonNode renameOp = paths.path("/api/v1/boards/{boardId}").path("patch");
		assertThat(renameOp.isMissingNode()).isFalse();
		assertThat(renameOp.path("summary").asText()).isNotBlank();
		assertThat(renameOp.path("tags").toString()).contains("보드");
		assertThat(renameOp.has("security")).isFalse();

		JsonNode deleteOp = paths.path("/api/v1/boards/{boardId}").path("delete");
		assertThat(deleteOp.isMissingNode()).isFalse();
		assertThat(deleteOp.path("summary").asText()).isNotBlank();
		assertThat(deleteOp.path("tags").toString()).contains("보드");
		assertThat(deleteOp.has("security")).isFalse();
	}
}
