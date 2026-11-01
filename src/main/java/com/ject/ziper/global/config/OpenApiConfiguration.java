package com.ject.ziper.global.config;

import java.util.Arrays;

import org.springdoc.core.filters.OpenApiMethodFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {
	public static final String[] PATHS = {"/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui.html",
			"/swagger-ui/**"};
	private final boolean enabled;

	public OpenApiConfiguration(Environment environment) {
		boolean api = environment.getProperty("springdoc.api-docs.enabled", Boolean.class, false);
		boolean ui = environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class, false);
		boolean local = Arrays.equals(environment.getActiveProfiles(), new String[]{"local"})
				&& "local".equals(environment.getProperty("app.runtime-mode", "server"));
		if ((api || ui) && !local) {
			throw new IllegalStateException("Swagger requires sole local profile and local runtime mode");
		}
		enabled = api && ui && local;
	}

	public boolean enabled() {
		return enabled;
	}

	@Bean
	OpenAPI openAPI() {
		return new OpenAPI()
				.components(new Components().addSecuritySchemes("bearerJWT",
						new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
				.addSecurityItem(new SecurityRequirement().addList("bearerJWT"));
	}

	@Bean
	OpenApiMethodFilter businessControllersOnly() {
		return method -> method.getDeclaringClass().getPackageName().startsWith("com.ject.ziper.domain.")
				&& method.getDeclaringClass().getPackageName().endsWith(".controller");
	}
}
