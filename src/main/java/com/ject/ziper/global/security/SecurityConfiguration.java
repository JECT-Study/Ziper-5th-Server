package com.ject.ziper.global.security;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {
	private static final String[] PROBES = {"/livez", "/readyz", "/actuator/health/liveness",
		"/actuator/health/readiness"};

	@Bean
	JwtDecoder jwtDecoder(SecurityProperties properties) {
		var key = new SecretKeySpec(Base64.getDecoder().decode(properties.jwtSecretBase64()), "HmacSHA256");
		var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
			JwtValidators.createDefaultWithIssuer(properties.jwtIssuer()),
			jwt -> jwt.getExpiresAt() != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()
				&& jwt.getAudience() != null && jwt.getAudience().contains(properties.jwtAudience())
				? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid required claims", null))));
		return decoder;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.cors(cors -> {})
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(authorize -> authorize
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers(HttpMethod.GET, PROBES).permitAll()
				.requestMatchers(HttpMethod.HEAD, PROBES).permitAll()
				.requestMatchers("/actuator/**").denyAll()
				.anyRequest().authenticated())
			.exceptionHandling(errors -> errors
				.authenticationEntryPoint((request, response, exception) -> writeProblem(mapper, response, HttpStatus.UNAUTHORIZED))
				.accessDeniedHandler((request, response, exception) -> writeProblem(mapper, response, HttpStatus.FORBIDDEN)))
			.oauth2ResourceServer(resource -> resource
				.bearerTokenResolver(new DefaultBearerTokenResolver())
				.jwt(jwt -> {})
				.authenticationEntryPoint((request, response, exception) -> writeProblem(mapper, response, HttpStatus.UNAUTHORIZED))
				.accessDeniedHandler((request, response, exception) -> writeProblem(mapper, response, HttpStatus.FORBIDDEN)));
		return http.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
		var cors = new CorsConfiguration();
		cors.setAllowedOrigins(properties.allowedOrigins());
		cors.setAllowCredentials(false);
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
		var source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);
		return source;
	}

	private static void writeProblem(ObjectMapper mapper, HttpServletResponse response, HttpStatus status)
		throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		if (status == HttpStatus.UNAUTHORIZED) {
			response.setHeader("WWW-Authenticate", "Bearer");
		}
		mapper.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status,
			status == HttpStatus.UNAUTHORIZED ? "Authentication is required" : "Access is denied"));
	}
}
