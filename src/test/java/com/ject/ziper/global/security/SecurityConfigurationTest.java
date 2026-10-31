package com.ject.ziper.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

@SpringBootTest(classes = SecurityConfigurationTest.Fixture.class, properties = {
		"app.security.jwt-secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"app.security.jwt-issuer=ziper-server", "app.security.jwt-audience=ziper-api",
		"app.security.allowed-origins=https://app.example.com", "spring.flyway.enabled=false", "sentry.enabled=false",
		"management.endpoint.health.group.readiness.include=readinessState"})
@AutoConfigureMockMvc
class SecurityConfigurationTest {
	private static final byte[] KEY = "0123456789abcdef0123456789abcdef"
			.getBytes(java.nio.charset.StandardCharsets.UTF_8);

	@Autowired
	MockMvc mvc;

	@Test
	void validSignedTokenAuthenticatesWithoutSession() throws Exception {
		var result = mvc.perform(get("/private").header("Authorization", "Bearer " + token(claims(), KEY)))
				.andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie")).andReturn();
		assertThat(result.getRequest().getSession(false)).isNull();
		mvc.perform(get("/private")).andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer")).andExpect(jsonPath("status").value(401))
				.andExpect(jsonPath("detail").value("Authentication is required"));
	}

	@Test
	void invalidClaimsAndSignaturesAreRejected() throws Exception {
		List<JWTClaimsSet.Builder> invalid = List.of(
				claims().expirationTime(Date.from(Instant.now().minusSeconds(300))), claims().expirationTime(null),
				claims().issuer("wrong"), claims().audience("wrong"), claims().audience((String) null),
				claims().subject(null), claims().subject(" "),
				claims().notBeforeTime(Date.from(Instant.now().plusSeconds(300))));
		for (var claims : invalid) {
			mvc.perform(get("/private").header("Authorization", "Bearer " + token(claims, KEY)))
					.andExpect(status().isUnauthorized()).andExpect(jsonPath("status").value(401))
					.andExpect(header().doesNotExist("Set-Cookie"));
		}
		mvc.perform(get("/private").header("Authorization", "Bearer broken")).andExpect(status().isUnauthorized());
		mvc.perform(get("/private").header("Authorization", "Bearer " + token(claims(), new byte[32])))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void onlyAuthorizationHeaderCarriesTokens() throws Exception {
		String token = token(claims(), KEY);
		mvc.perform(get("/private").param("access_token", token)).andExpect(status().isUnauthorized());
		mvc.perform(get("/private").cookie(new Cookie("access_token", token))).andExpect(status().isUnauthorized());
	}

	@Test
	void probesArePublicOnlyForGetAndHead() throws Exception {
		for (String path : List.of("/livez", "/readyz", "/actuator/health/liveness", "/actuator/health/readiness")) {
			mvc.perform(get(path)).andExpect(status().isOk());
			mvc.perform(head(path)).andExpect(status().isOk());
			mvc.perform(post(path)).andExpect(status().isUnauthorized());
		}
		mvc.perform(get("/actuator/env").header("Authorization", "Bearer " + token(claims(), KEY)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("status").value(403));
		mvc.perform(get("/actuator/health").header("Authorization", "Bearer " + token(claims(), KEY)))
				.andExpect(status().isForbidden());
		mvc.perform(get("/error")).andExpect(status().isUnauthorized());
		mvc.perform(get("/private").with(request -> {
			request.setDispatcherType(DispatcherType.ERROR);
			return request;
		})).andExpect(status().isOk());
	}

	@Test
	void corsAllowsOnlyConfiguredOriginsWithoutCredentials() throws Exception {
		mvc.perform(options("/private").header("Origin", "https://app.example.com")
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "Authorization, Content-Type")).andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"))
				.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
		mvc.perform(options("/private").header("Origin", "https://evil.example.com")
				.header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	void propertiesRejectInvalidSecretsAndWildcardOrigins() {
		for (String secret : List.of("", "not-base64!", Base64.getEncoder().encodeToString(new byte[31]))) {
			new ApplicationContextRunner().withUserConfiguration(PropertiesFixture.class)
					.withPropertyValues("app.security.jwt-secret-base64=" + secret)
					.run(context -> assertThat(context).hasFailed());
		}
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new SecurityProperties(Base64.getEncoder().encodeToString(KEY), null, null,
						List.of("https://*.example.com")));
	}

	private JWTClaimsSet.Builder claims() {
		return new JWTClaimsSet.Builder().issuer("ziper-server").audience("ziper-api").subject("member-1")
				.expirationTime(Date.from(Instant.now().plusSeconds(300)));
	}

	private String token(JWTClaimsSet.Builder claims, byte[] key) throws Exception {
		var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
		jwt.sign(new MACSigner(key));
		return jwt.serialize();
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(SecurityProperties.class)
	static class PropertiesFixture {
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
	@Import({SecurityConfiguration.class, Endpoints.class})
	static class Fixture {
	}

	@RestController
	static class Endpoints {
		@GetMapping({"/private", "/livez", "/readyz", "/actuator/health/liveness", "/actuator/health/readiness"})
		String ok() {
			return "ok";
		}
	}
}
