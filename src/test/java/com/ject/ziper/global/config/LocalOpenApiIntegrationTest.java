package com.ject.ziper.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.ject.ziper.global.error.ProblemFactory;
import com.ject.ziper.global.security.SecurityConfiguration;

@SpringBootTest(classes = LocalOpenApiIntegrationTest.Fixture.class, properties = {"app.runtime-mode=local",
		"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true",
		"app.security.jwt-secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "spring.flyway.enabled=false",
		"sentry.enabled=false", "management.endpoint.health.group.readiness.include=readinessState"})
@ActiveProfiles("local")
@AutoConfigureMockMvc
class LocalOpenApiIntegrationTest {
	@Autowired
	MockMvc mvc;

	@Test
	void localOptInPublishesUiAssetsAndBothFormatsWhileApisRequireAuthentication() throws Exception {
		mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
		mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
		mvc.perform(get("/swagger-ui/swagger-ui-bundle.js")).andExpect(status().isOk());
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andExpect(jsonPath("components.securitySchemes.bearerJWT.bearerFormat").value("JWT"))
				.andExpect(jsonPath("paths").isEmpty());
		mvc.perform(head("/v3/api-docs")).andExpect(status().isOk());
		mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
		mvc.perform(get("/private")).andExpect(status().isUnauthorized());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
	@Import({OpenApiConfiguration.class, SecurityConfiguration.class, ProblemFactory.class})
	static class Fixture {
	}
}
