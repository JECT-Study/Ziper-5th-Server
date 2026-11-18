package com.ject.ziper.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.global.persistence.PostgresTestSupport;

@EnabledIfEnvironmentVariable(named = "OPENAPI_EXPORT_DIR", matches = ".+")
@SpringBootTest(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true",
		"app.runtime-mode=local", "app.mail.enabled=true", "app.mail.from=developer@example.com",
		"app.security.jwt-secret-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
		"app.mail.code-secret-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=", "spring.mail.host=localhost"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class OpenApiSpecExportTest extends PostgresTestSupport {
	private static final List<String> UI_ASSETS = List.of("swagger-ui.css", "index.css", "swagger-ui-bundle.js",
			"swagger-ui-standalone-preset.js", "favicon-32x32.png", "favicon-16x16.png");

	@Autowired
	private MockMvc mvc;
	@Autowired
	private ObjectMapper mapper;

	@Test
	void 정적OpenAPI내보내기실행시_문서와UI자산을요청하면_정적자산을추출한다() throws Exception {
		Path exportDir = Path.of(System.getenv("OPENAPI_EXPORT_DIR")).toAbsolutePath().normalize();
		Files.createDirectories(exportDir);

		String rawJson = mvc.perform(get("/v3/api-docs").with(request -> {
			request.setServerPort(8080);
			return request;
		})).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		JsonNode tree = mapper.readTree(rawJson);
		assertThat(tree.path("openapi").asText()).startsWith("3.");
		assertThat(tree.path("paths").isObject()).isTrue();
		assertThat(tree.path("paths").isEmpty()).isFalse();
		String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree) + System.lineSeparator();
		Files.writeString(exportDir.resolve("openapi.json"), prettyJson, StandardCharsets.UTF_8);

		String yaml = mvc.perform(get("/v3/api-docs.yaml").with(request -> {
			request.setServerPort(8080);
			return request;
		})).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		assertThat(yaml).isNotBlank();
		Files.writeString(exportDir.resolve("openapi.yaml"), yaml, StandardCharsets.UTF_8);

		for (String asset : UI_ASSETS) {
			byte[] bytes = mvc.perform(get("/swagger-ui/" + asset)).andExpect(status().isOk()).andReturn().getResponse()
					.getContentAsByteArray();
			assertThat(bytes).as("Swagger UI asset %s", asset).isNotEmpty();
			Files.write(exportDir.resolve(asset), bytes);
		}
	}
}
