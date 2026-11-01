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

import java.nio.charset.StandardCharsets;
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

import com.ject.ziper.global.config.OpenApiConfiguration;
import com.ject.ziper.global.error.ProblemFactory;
import com.ject.ziper.global.observability.RequestIdFilter;
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
	private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

	@Autowired
	MockMvc mvc;

	@Test
	void 유효한서명토큰또는토큰누락_보호경로를요청하면_유효토큰은세션없이인증하고누락은401로거부한다() throws Exception {
		var result = mvc.perform(get("/private").header("Authorization", "Bearer " + token(claims(), KEY)))
				.andExpect(status().isOk()).andExpect(header().doesNotExist("Set-Cookie")).andReturn();
		assertThat(result.getRequest().getSession(false)).isNull();
		mvc.perform(get("/private")).andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer")).andExpect(jsonPath("status").value(401))
				.andExpect(jsonPath("detail").value("인증이 필요합니다."));
	}

	@Test
	void 잘못된클레임이나서명의토큰_보호경로를요청하면_401로거부한다() throws Exception {
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
	void 쿼리파라미터나쿠키에담긴토큰_보호경로를요청하면_401로거부한다() throws Exception {
		String token = token(claims(), KEY);
		mvc.perform(get("/private").param("access_token", token)).andExpect(status().isUnauthorized());
		mvc.perform(get("/private").cookie(new Cookie("access_token", token))).andExpect(status().isUnauthorized());
	}

	@Test
	void 프로브와actuator와오류경로_메서드와인증과디스패치를바꿔요청하면_프로브GET과HEAD와오류디스패치만통과하고actuator는인증해도거부한다() throws Exception {
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
	void 허용되거나허용되지않은Origin_CORS사전요청을보내면_허용Origin만자격증명없이통과한다() throws Exception {
		mvc.perform(options("/private").header("Origin", "https://app.example.com")
				.header("Access-Control-Request-Method", "POST")
				.header("Access-Control-Request-Headers", "Authorization, Content-Type")).andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "https://app.example.com"))
				.andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
		mvc.perform(options("/private").header("Origin", "https://evil.example.com")
				.header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
				.andExpect(jsonPath("code").value("FORBIDDEN")).andExpect(jsonPath("requestId").isNotEmpty());
	}

	@Test
	void Swagger가비활성화된환경_인증여부와관계없이문서를요청하면_접근을거부한다() throws Exception {
		for (String path : List.of("/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/index.html",
				"/swagger-ui/swagger-ui-bundle.js")) {
			mvc.perform(get(path)).andExpect(status().isUnauthorized());
			mvc.perform(get(path).header("Authorization", "Bearer " + token(claims(), KEY)))
					.andExpect(status().isForbidden());
		}
	}

	@Test
	void 짧거나잘못된JWT비밀값또는와일드카드Origin_보안설정을생성하면_실패한다() {
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
	@Import({SecurityConfiguration.class, Endpoints.class, ProblemFactory.class, OpenApiConfiguration.class,
			RequestIdFilter.class})
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
