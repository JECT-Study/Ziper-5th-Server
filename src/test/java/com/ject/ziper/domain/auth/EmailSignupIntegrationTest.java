package com.ject.ziper.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.domain.auth.service.EmailSignupService;
import com.ject.ziper.global.persistence.PostgresTestSupport;

@SpringBootTest(properties = {"app.mail.enabled=true", "app.mail.from=developer@example.com",
		"app.mail.code-secret-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=", "spring.mail.host=localhost"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class EmailSignupIntegrationTest extends PostgresTestSupport {
	private static final String VERIFICATIONS = "/api/v1/auth/email/verifications";
	private static final String SIGNUP = "/api/v1/auth/email/signup";
	private static final String EMAIL = "member@example.com";
	private static final String PASSWORD = "abcd1234!";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ObjectMapper mapper;
	@Autowired
	private EmailSignupService signupService;
	@MockitoBean
	private JavaMailSender mailSender;

	@BeforeEach
	@AfterEach
	void clear() {
		jdbc.update("DELETE FROM ziper.users");
		jdbc.update("DELETE FROM ziper.oauth_users");
		jdbc.update("DELETE FROM ziper.auth_codes");
		jdbc.update("DELETE FROM ziper.email_verification_failures");
	}

	@Test
	void 인증을마친흐름이주어졌을때_가입하면_회원과기본보드와세션을만들고토큰을발급한다() throws Exception {
		UUID flowId = verifiedFlow(EMAIL);
		MvcResult result = mvc
				.perform(post(SIGNUP).header("User-Agent", "ziper-ios").contentType("application/json")
						.content(body(flowId, PASSWORD, "감자", true, true, true)))
				.andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.accessToken").isNotEmpty()).andExpect(jsonPath("$.refreshToken").isNotEmpty())
				.andExpect(jsonPath("$.agreements.termsOfService").value(true))
				.andExpect(jsonPath("$.agreements.privacyPolicy").value(true))
				.andExpect(jsonPath("$.agreements.marketing").value(true))
				.andExpect(jsonPath("$.agreements.termsAgreedAt").isNotEmpty()).andReturn();

		String passwordHash = jdbc.queryForObject("SELECT password_hash FROM ziper.users WHERE email = ?", String.class,
				EMAIL);
		assertThat(passwordHash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
		assertThat(jdbc.queryForObject("SELECT nickname FROM ziper.users WHERE email = ?", String.class, EMAIL))
				.isEqualTo("감자");
		assertThat(
				jdbc.queryForObject("SELECT marketing_agreed FROM ziper.users WHERE email = ?", Boolean.class, EMAIL))
				.isTrue();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ziper.product_boards", Integer.class)).isOne();
		assertThat(jdbc.queryForObject("SELECT user_agent FROM ziper.sessions", String.class)).isEqualTo("ziper-ios");
		assertThat(jdbc.queryForObject(
				"SELECT consumed_at IS NOT NULL FROM ziper.auth_codes WHERE flow_id = ? ORDER BY id DESC LIMIT 1",
				Boolean.class, flowId)).isTrue();

		String refreshToken = mapper.readTree(result.getResponse().getContentAsString()).get("refreshToken").asText();
		mvc.perform(post("/api/v1/auth/reissue").contentType("application/json")
				.content("{\"refreshToken\":\"" + refreshToken + "\"}")).andExpect(status().isOk());
	}

	@Test
	void 가입에사용한흐름이주어졌을때_다시가입하거나재전송하면_흐름오류로거부한다() throws Exception {
		UUID flowId = verifiedFlow(EMAIL);
		signup(flowId, PASSWORD, "감자").andExpect(status().isCreated());

		signup(flowId, PASSWORD, "고구마").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EMAIL_FLOW_INVALID"));
		mvc.perform(post(VERIFICATIONS + "/" + flowId + "/resend")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EMAIL_FLOW_INVALID"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ziper.users", Integer.class)).isOne();
	}

	@Test
	void 인증하지않았거나없는흐름이주어졌을때_가입하면_흐름오류로거부한다() throws Exception {
		UUID unverified = issue(EMAIL);
		signup(unverified, PASSWORD, "감자").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EMAIL_FLOW_INVALID"));
		signup(UUID.randomUUID(), PASSWORD, "감자").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EMAIL_FLOW_INVALID"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ziper.users", Integer.class)).isZero();
	}

	@Test
	void 인증후삼십분이지났을때_가입하면_흐름오류로거부한다() throws Exception {
		UUID flowId = verifiedFlow(EMAIL);
		jdbc.update("UPDATE ziper.auth_codes SET verified_at = verified_at - INTERVAL '31 minutes' WHERE flow_id = ?",
				flowId);
		signup(flowId, PASSWORD, "감자").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EMAIL_FLOW_INVALID"));
	}

	@Test
	void 이미가입한이메일이주어졌을때_인증번호를요청하면_발급하지않고충돌로거부한다() throws Exception {
		jdbc.update("INSERT INTO ziper.users (email, nickname) VALUES (?, ?)", "Member@Example.com", "기존회원");
		mvc.perform(post(VERIFICATIONS).contentType("application/json").content("{\"email\":\"" + EMAIL + "\"}"))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ziper.auth_codes", Integer.class)).isZero();
	}

	@Test
	void 흐름진행중다른경로로같은이메일이가입됐을때_재전송하거나가입하면_충돌로거부한다() throws Exception {
		UUID pending = issue(EMAIL);
		UUID verified = verifiedFlow("second@example.com");
		jdbc.update("INSERT INTO ziper.users (email, nickname) VALUES (?, ?)", EMAIL, "선점회원");
		jdbc.update("INSERT INTO ziper.users (email, nickname) VALUES (?, ?)", "second@example.com", "선점회원2");
		jdbc.update("UPDATE ziper.auth_codes SET requested_at = requested_at - INTERVAL '4 minutes'");

		mvc.perform(post(VERIFICATIONS + "/" + pending + "/resend")).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
		signup(verified, PASSWORD, "감자").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"abcd123", "abcdefgh!", "12345678!", "abcd1234", "abcd 1234!", "비밀번호abc1!",
			"a1!aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
	void 규칙에맞지않는비밀번호가주어졌을때_가입하면_비밀번호오류로거부한다(String password) throws Exception {
		UUID flowId = verifiedFlow(EMAIL);
		signup(flowId, password, "감자").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ziper.users", Integer.class)).isZero();
	}

	@Test
	void 중복되거나형식이틀린닉네임이주어졌을때_가입하면_닉네임오류로거부하고흐름을유지한다() throws Exception {
		jdbc.update("INSERT INTO ziper.users (email, nickname) VALUES (?, ?)", "taken@example.com", "감자");
		UUID flowId = verifiedFlow(EMAIL);
		signup(flowId, PASSWORD, "감자").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"));
		signup(flowId, PASSWORD, "감 자!").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_NICKNAME"));
		signup(flowId, PASSWORD, "고구마").andExpect(status().isCreated());
	}

	@Test
	void 필수약관에동의하지않았을때_가입하면_요청오류로거부한다() throws Exception {
		UUID flowId = verifiedFlow(EMAIL);
		mvc.perform(
				post(SIGNUP).contentType("application/json").content(body(flowId, PASSWORD, "감자", true, false, false)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mvc.perform(
				post(SIGNUP).contentType("application/json").content(body(flowId, PASSWORD, "감자", false, true, false)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ziper.users", Integer.class)).isZero();
	}

	@Test
	void 같은흐름으로동시에가입했을때_처리하면_한번만가입한다() throws Exception {
		UUID flowId = verifiedFlow(EMAIL);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Callable<Boolean>> tasks = new ArrayList<>();
			tasks.add(() -> attemptSignup(flowId, "감자"));
			tasks.add(() -> attemptSignup(flowId, "고구마"));
			int succeeded = 0;
			for (Future<Boolean> future : executor.invokeAll(tasks)) {
				succeeded += future.get() ? 1 : 0;
			}
			assertThat(succeeded).isOne();
		} finally {
			executor.shutdownNow();
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ziper.users", Integer.class)).isOne();
	}

	private boolean attemptSignup(UUID flowId, String nickname) {
		try {
			signupService.signup(flowId, PASSWORD, nickname, false, null);
			return true;
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private ResultActions signup(UUID flowId, String password, String nickname) throws Exception {
		return mvc.perform(post(SIGNUP).contentType("application/json")
				.content(body(flowId, password, nickname, true, true, false)));
	}

	private String body(UUID flowId, String password, String nickname, boolean terms, boolean privacy,
			boolean marketing) throws Exception {
		JsonNode agreements = mapper.createObjectNode().put("termsOfService", terms).put("privacyPolicy", privacy)
				.put("marketing", marketing);
		return mapper.writeValueAsString(mapper.createObjectNode().put("flowId", flowId.toString())
				.put("password", password).put("nickname", nickname).set("agreements", agreements));
	}

	private UUID verifiedFlow(String email) throws Exception {
		UUID flowId = issue(email);
		mvc.perform(post(VERIFICATIONS + "/" + flowId + "/verify").contentType("application/json")
				.content("{\"code\":\"" + sentCode() + "\"}")).andExpect(status().isNoContent());
		mvc.perform(get(VERIFICATIONS + "/" + flowId)).andExpect(jsonPath("$.verified").value(true));
		return flowId;
	}

	private UUID issue(String email) throws Exception {
		MvcResult result = mvc
				.perform(post(VERIFICATIONS).contentType("application/json").content("{\"email\":\"" + email + "\"}"))
				.andExpect(status().isCreated()).andReturn();
		return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("flowId").asText());
	}

	private String sentCode() {
		ArgumentCaptor<SimpleMailMessage> messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender, atLeastOnce()).send(messages.capture());
		Matcher matcher = Pattern.compile("인증번호: ([0-9]{6})").matcher(messages.getAllValues().getLast().getText());
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}
}
