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
class PurchasedProductOpenApiTest extends PostgresTestSupport {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper mapper;

	@Test
	void 로컬문서활성화상태에서_스펙을조회하면_구매전환과목록정렬계약을노출한다() throws Exception {
		JsonNode root = mapper.readTree(mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString());
		JsonNode purchase = root.path("paths").path("/api/v1/products/{cardId}/purchase").path("post");
		JsonNode list = root.path("paths").path("/api/v1/products/purchased").path("get");
		assertThat(purchase.path("summary").asText()).contains("구매 완료 전환");
		assertThat(purchase.path("responses").has("200")).isTrue();
		assertThat(list.path("summary").asText()).contains("구매 완료 상품 목록");
		assertThat(list.path("description").asText()).contains("4종 정렬");
		assertThat(list.path("parameters").toString()).contains("sort", "cursor", "size");
	}
}
