package com.ject.ziper.global.security;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authorization.AuthorizationDecision;
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
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.global.config.OpenApiConfiguration;
import com.ject.ziper.global.error.ProblemFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfiguration {
	private static final String[] PROBES = {"/livez", "/readyz", "/actuator/health/liveness",
			"/actuator/health/readiness"};
	private static final String[] AUTH_PUBLIC_PATHS = {"/api/v1/auth/oauth/*", "/api/v1/auth/reissue",
			"/api/v1/auth/logout", "/api/v1/auth/email/verifications", "/api/v1/auth/email/verifications/*/resend",
			"/api/v1/auth/email/verifications/*/verify"};
	private static final String[] PUBLIC_GET_PATHS = {"/api/v1/users/nickname/availability",
			"/api/v1/auth/email/verifications/*"};

	@Bean
	JwtDecoder jwtDecoder(SecurityProperties properties) {
		var key = new SecretKeySpec(Base64.getDecoder().decode(properties.jwtSecretBase64()), "HmacSHA256");
		var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(properties.jwtIssuer()),
				jwt -> jwt.getExpiresAt() != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()
						&& jwt.getAudience() != null && jwt.getAudience().contains(properties.jwtAudience())
						&& !JwtTokenProvider.TOKEN_TYPE_REFRESH
								.equals(jwt.getClaimAsString(JwtTokenProvider.CLAIM_TOKEN_TYPE))
										? OAuth2TokenValidatorResult.success()
										: OAuth2TokenValidatorResult.failure(
												new OAuth2Error("invalid_token", "Invalid required claims", null))));
		return decoder;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper, ProblemFactory problems,
			OpenApiConfiguration docs, CorsConfigurationSource corsConfigurationSource) throws Exception {
		var corsFilter = new CorsFilter(corsConfigurationSource);
		corsFilter.setCorsProcessor(new DefaultCorsProcessor() {
			@Override
			protected void rejectRequest(ServerHttpResponse response) throws IOException {
				response.setStatusCode(HttpStatus.FORBIDDEN);
			}
			@Override
			public boolean processRequest(CorsConfiguration configuration, HttpServletRequest request,
					HttpServletResponse response) throws IOException {
				boolean accepted = super.processRequest(configuration, request, response);
				if (!accepted) {
					problems.write(mapper, request, response, 403);
				}
				return accepted;
			}
		});
		http.addFilterAt(corsFilter, CorsFilter.class);
		http.csrf(AbstractHttpConfigurer::disable).cors(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(authorize -> authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.GET, PROBES).permitAll().requestMatchers(HttpMethod.HEAD, PROBES)
						.permitAll().requestMatchers(HttpMethod.POST, AUTH_PUBLIC_PATHS).permitAll()
						.requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS).permitAll().requestMatchers("/actuator/**")
						.denyAll().requestMatchers(HttpMethod.GET, OpenApiConfiguration.PATHS)
						.access((authentication, context) -> new AuthorizationDecision(docs.enabled()))
						.requestMatchers(HttpMethod.HEAD, OpenApiConfiguration.PATHS)
						.access((authentication, context) -> new AuthorizationDecision(docs.enabled()))
						.requestMatchers(OpenApiConfiguration.PATHS).denyAll().anyRequest().authenticated())
				.exceptionHandling(errors -> errors
						.authenticationEntryPoint(
								(request, response, exception) -> problems.write(mapper, request, response, 401))
						.accessDeniedHandler(
								(request, response, exception) -> problems.write(mapper, request, response, 403)))
				.oauth2ResourceServer(
						resource -> resource.bearerTokenResolver(new DefaultBearerTokenResolver()).jwt(jwt -> {
						}).authenticationEntryPoint(
								(request, response, exception) -> problems.write(mapper, request, response, 401))
								.accessDeniedHandler((request, response, exception) -> problems.write(mapper, request,
										response, 403)));
		return http.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
		var cors = new CorsConfiguration();
		cors.setAllowedOrigins(properties.allowedOrigins());
		cors.setAllowCredentials(false);
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
		cors.setExposedHeaders(List.of("X-Request-Id"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "sentry-trace", "baggage"));
		var source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", cors);
		return source;
	}

}
