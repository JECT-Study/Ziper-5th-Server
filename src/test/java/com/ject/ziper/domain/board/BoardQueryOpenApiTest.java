package com.ject.ziper.domain.board;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
class BoardQueryOpenApiTest extends PostgresTestSupport {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper mapper;

	@Test
	void 로컬문서활성화상태에서_보드조회경로를조회하면_인증필요와한국어명세가포함된다() throws Exception {
		String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		JsonNode paths = mapper.readTree(body).path("paths");

		JsonNode listBoardsOp = paths.path("/api/v1/boards").path("get");
		assertThat(listBoardsOp.isMissingNode()).isFalse();
		assertThat(listBoardsOp.path("summary").asText()).contains("보드 목록 조회");
		assertThat(listBoardsOp.path("description").asText()).contains("순수 최신 생성순");
		assertThat(listBoardsOp.path("tags").toString()).contains("보드");
		assertThat(listBoardsOp.has("security")).isFalse();

		JsonNode boardProductsOp = paths.path("/api/v1/boards/{boardId}/products").path("get");
		assertThat(boardProductsOp.isMissingNode()).isFalse();
		assertThat(boardProductsOp.path("summary").asText()).contains("보드 상세 상품 목록 조회");
		assertThat(boardProductsOp.path("description").asText()).contains("정렬");
		assertThat(boardProductsOp.path("tags").toString()).contains("보드");
		assertThat(boardProductsOp.has("security")).isFalse();

		List<String> parameterNames = new ArrayList<>();
		for (JsonNode parameter : boardProductsOp.path("parameters")) {
			parameterNames.add(parameter.path("name").asText());
			assertThat(parameter.path("description").asText()).isNotBlank();
		}
		assertThat(parameterNames).contains("boardId", "sort", "tagIds", "cursor", "size");
	}
}
