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
class ProductCardDetailOpenApiTest extends PostgresTestSupport {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper mapper;

	@Test
	void 로컬문서활성화상태에서_스펙을조회하면_상품카드상세수정삭제원본이동API가한글문서와JWT인증으로노출된다() throws Exception {
		String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		JsonNode root = mapper.readTree(body);

		JsonNode cardPath = root.path("paths").path("/api/v1/products/{cardId}");
		JsonNode detailOperation = cardPath.path("get");
		assertThat(detailOperation.isMissingNode()).isFalse();
		assertThat(detailOperation.path("summary").asText()).contains("상품 카드 상세 조회");
		assertThat(detailOperation.path("description").asText()).isNotBlank();
		assertThat(detailOperation.has("security")).isFalse();

		JsonNode updateOperation = cardPath.path("patch");
		assertThat(updateOperation.isMissingNode()).isFalse();
		assertThat(updateOperation.path("summary").asText()).contains("상품 카드 정보 수정");
		assertThat(updateOperation.path("description").asText()).isNotBlank();
		assertThat(updateOperation.path("requestBody").path("required").asBoolean()).isTrue();
		assertThat(updateOperation.has("security")).isFalse();

		JsonNode deleteOperation = cardPath.path("delete");
		assertThat(deleteOperation.isMissingNode()).isFalse();
		assertThat(deleteOperation.path("summary").asText()).contains("상품 카드 전체 삭제");
		assertThat(deleteOperation.path("description").asText()).isNotBlank();
		assertThat(deleteOperation.has("security")).isFalse();

		JsonNode openOriginalOperation = root.path("paths").path("/api/v1/products/{cardId}/open-original")
				.path("post");
		assertThat(openOriginalOperation.isMissingNode()).isFalse();
		assertThat(openOriginalOperation.path("summary").asText()).contains("원본 쇼핑몰 이동");
		assertThat(openOriginalOperation.path("description").asText()).isNotBlank();
		assertThat(openOriginalOperation.has("security")).isFalse();

		assertThat(root.path("security").isArray()).isTrue();
		assertThat(root.path("security").toString()).contains("bearerJWT");
	}
}
