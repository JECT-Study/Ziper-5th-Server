package com.ject.ziper.domain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.global.persistence.PostgresTestSupport;

@SpringBootTest(properties = {"app.mail.enabled=true", "app.mail.from=developer@example.com",
		"app.mail.code-secret-base64=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=", "spring.mail.host=localhost"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class EmailVerificationIntegrationTest extends PostgresTestSupport {
	private static final String PATH = "/api/v1/auth/email/verifications";
	private static final String EMAIL = "member@example.com";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private ObjectMapper mapper;
	@MockitoBean
	private JavaMailSender mailSender;

	@BeforeEach
	void clearCodes() {
		jdbc.update("DELETE FROM ziper.auth_codes");
		jdbc.update("DELETE FROM ziper.email_verification_failures");
	}

	@Test
	void 이메일이주어졌을때_발급을요청하면_해시와만료를저장하고메일을보낸다() throws Exception {
		UUID flowId = issue(EMAIL);
		String code = sentCode();
		assertThat(code).matches("[0-9]{6}");
		String hash = jdbc.queryForObject("SELECT code_hash FROM ziper.auth_codes WHERE flow_id = ?", String.class,
				flowId);
		assertThat(hash).isNotEqualTo(code);
		Instant expiry = jdbc.queryForObject("SELECT expired_at FROM ziper.auth_codes WHERE flow_id = ?",
				OffsetDateTime.class, flowId).toInstant();
		assertThat(expiry).isAfter(Instant.now().plusSeconds(170)).isBefore(Instant.now().plusSeconds(190));
	}

	@Test
	void 삼분이지나지않았을때_재전송을요청하면_거부하고삼분뒤발송한다() throws Exception {
		UUID flowId = issue(EMAIL);
		mvc.perform(post(PATH + "/" + flowId + "/resend")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("EMAIL_REQUEST_LIMITED"));
		ageLatest(flowId);
		mvc.perform(post(PATH + "/" + flowId + "/resend")).andExpect(status().isNoContent());
		assertThat(
				jdbc.queryForObject("SELECT COUNT(*) FROM ziper.auth_codes WHERE flow_id = ?", Integer.class, flowId))
				.isEqualTo(2);
	}

	@Test
	void 번호를다섯번틀렸을때_재전송해도_실패횟수를유지하고차단한다() throws Exception {
		UUID flowId = issue(EMAIL);
		String wrongCode = sentCode().equals("000000") ? "111111" : "000000";
		for (int attempt = 1; attempt < 5; attempt++) {
			mvc.perform(post(PATH + "/" + flowId + "/verify").contentType("application/json")
					.content("{\"code\":\"" + wrongCode + "\"}")).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("EMAIL_CODE_INVALID"));
		}
		ageLatest(flowId);
		mvc.perform(post(PATH + "/" + flowId + "/resend")).andExpect(status().isNoContent());
		wrongCode = sentCode().equals("000000") ? "111111" : "000000";
		mvc.perform(post(PATH + "/" + flowId + "/verify").contentType("application/json")
				.content("{\"code\":\"" + wrongCode + "\"}")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("EMAIL_CODE_ATTEMPTS_EXCEEDED"));
		mvc.perform(get(PATH + "/" + flowId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.failedAttempts").value(5)).andExpect(jsonPath("$.remainingAttempts").value(0));
		assertThat(jdbc.queryForObject("SELECT SUM(failed_attempts) FROM ziper.auth_codes WHERE flow_id = ?",
				Integer.class, flowId)).isEqualTo(5);
		mvc.perform(post(PATH + "/" + flowId + "/resend")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("EMAIL_CODE_ATTEMPTS_EXCEEDED"));
	}

	@Test
	void 같은이메일로다섯번요청했을때_삼분간격을지켜추가발급하면_이메일시간당한도없이발송한다() throws Exception {
		for (int request = 0; request < 6; request++) {
			ageLatest(issue(EMAIL));
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ziper.auth_codes WHERE email = ?", Integer.class, EMAIL))
				.isEqualTo(6);
	}

	@Test
	void 한흐름에서네번틀리고새흐름에서한번더틀렸을때_발급재전송확인을요청하면_이메일단위로잠겨모두거부한다() throws Exception {
		UUID first = issue(EMAIL);
		failVerify(first, 4);
		ageLatest(first);
		UUID second = issue(EMAIL);
		String code = sentCode();
		mvc.perform(post(PATH + "/" + second + "/verify").contentType("application/json")
				.content("{\"code\":\"" + wrongCodeFor(code) + "\"}")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("EMAIL_CODE_ATTEMPTS_EXCEEDED"));
		mvc.perform(post(PATH + "/" + second + "/verify").contentType("application/json")
				.content("{\"code\":\"" + code + "\"}")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("EMAIL_CODE_ATTEMPTS_EXCEEDED"));
		ageLatest(second);
		mvc.perform(post(PATH + "/" + second + "/resend")).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("EMAIL_CODE_ATTEMPTS_EXCEEDED"));
		mvc.perform(post(PATH).contentType("application/json").content("{\"email\":\"Member@Example.com\"}"))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("EMAIL_CODE_ATTEMPTS_EXCEEDED"));
		MvcResult response = mvc.perform(get(PATH + "/" + second)).andExpect(status().isOk())
				.andExpect(jsonPath("$.failedAttempts").value(5)).andExpect(jsonPath("$.remainingAttempts").value(0))
				.andReturn();
		Instant lockedUntil = Instant
				.parse(mapper.readTree(response.getResponse().getContentAsString()).get("lockedUntil").asText());
		assertThat(lockedUntil).isAfter(Instant.now().plusSeconds(29 * 60))
				.isBefore(Instant.now().plusSeconds(31 * 60));
		issue("other@example.com");
	}

	@Test
	void 잠기고삼십분이지났을때_같은이메일로다시인증하면_횟수를초기화하고성공한다() throws Exception {
		UUID locked = issue(EMAIL);
		failVerify(locked, 5);
		ageLatest(locked);
		ageFailures(31);
		UUID retry = issue(EMAIL);
		mvc.perform(get(PATH + "/" + retry)).andExpect(status().isOk()).andExpect(jsonPath("$.failedAttempts").value(0))
				.andExpect(jsonPath("$.remainingAttempts").value(5)).andExpect(jsonPath("$.lockedUntil").isEmpty());
		String code = sentCode();
		mvc.perform(post(PATH + "/" + retry + "/verify").contentType("application/json")
				.content("{\"code\":\"" + wrongCodeFor(code) + "\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EMAIL_CODE_INVALID"));
		mvc.perform(get(PATH + "/" + retry)).andExpect(jsonPath("$.failedAttempts").value(1));
		mvc.perform(post(PATH + "/" + retry + "/verify").contentType("application/json")
				.content("{\"code\":\"" + code + "\"}")).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ziper.email_verification_failures", Integer.class))
				.isZero();
	}

	@Test
	void 잠기지않은실패후삼십분이지났을때_다시틀리면_첫번째실패로센다() throws Exception {
		UUID flowId = issue(EMAIL);
		failVerify(flowId, 4);
		ageFailures(31);
		failVerify(flowId, 1);
		mvc.perform(get(PATH + "/" + flowId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.failedAttempts").value(1)).andExpect(jsonPath("$.remainingAttempts").value(4));
	}

	@Test
	void 같은출처로스무번요청했을때_추가발급을요청하면_시간당한도로거부한다() throws Exception {
		for (int request = 0; request < 20; request++) {
			issue("member" + request + "@example.com");
		}
		mvc.perform(post(PATH).contentType("application/json").content("{\"email\":\"overflow@example.com\"}"))
				.andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("EMAIL_REQUEST_LIMITED"));
	}

	@Test
	void 만료후재전송했을때_최신번호를확인하면_성공하고이전번호를거부한다() throws Exception {
		UUID flowId = issue(EMAIL);
		String firstCode = sentCode();
		ageLatest(flowId);
		mvc.perform(post(PATH + "/" + flowId + "/verify").contentType("application/json")
				.content("{\"code\":\"" + firstCode + "\"}")).andExpect(status().isGone());
		mvc.perform(post(PATH + "/" + flowId + "/resend")).andExpect(status().isNoContent());
		mvc.perform(post(PATH + "/" + flowId + "/verify").contentType("application/json")
				.content("{\"code\":\"" + firstCode + "\"}")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("EMAIL_CODE_INVALID"));
		String latest = sentCode();
		mvc.perform(post(PATH + "/" + flowId + "/verify").contentType("application/json")
				.content("{\"code\":\"" + latest + "\"}")).andExpect(status().isNoContent());
		mvc.perform(get(PATH + "/" + flowId)).andExpect(status().isOk()).andExpect(jsonPath("$.verified").value(true))
				.andExpect(jsonPath("$.remainingSeconds").value(0));
		assertThat(jdbc.queryForObject(
				"SELECT verified_at FROM ziper.auth_codes WHERE flow_id = ? " + "ORDER BY id DESC LIMIT 1",
				OffsetDateTime.class, flowId)).isNotNull();
	}

	@Test
	void 번호를한번틀렸을때_인증상태를조회하면_실패횟수와남은시간을응답한다() throws Exception {
		UUID flowId = issue(EMAIL);
		String wrongCode = sentCode().equals("000000") ? "111111" : "000000";
		mvc.perform(post(PATH + "/" + flowId + "/verify").contentType("application/json")
				.content("{\"code\":\"" + wrongCode + "\"}")).andExpect(status().isBadRequest());
		MvcResult response = mvc.perform(get(PATH + "/" + flowId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.failedAttempts").value(1)).andExpect(jsonPath("$.remainingAttempts").value(4))
				.andExpect(jsonPath("$.email").doesNotExist()).andReturn();
		assertThat(mapper.readTree(response.getResponse().getContentAsString()).get("remainingSeconds").asLong())
				.isBetween(1L, 180L);
		assertThat(mapper.readTree(response.getResponse().getContentAsString()).get("expiresAt").asText()).isNotBlank();
		ageLatest(flowId);
		mvc.perform(get(PATH + "/" + flowId)).andExpect(status().isOk())
				.andExpect(jsonPath("$.remainingSeconds").value(0));
	}

	@Test
	void 메일발송이실패했을때_발급을요청하면_요청기록을남기고서버오류를응답한다() throws Exception {
		doThrow(new MailSendException("SMTP unavailable")).when(mailSender).send(any(SimpleMailMessage.class));
		mvc.perform(post(PATH).contentType("application/json").content("{\"email\":\"" + EMAIL + "\"}"))
				.andExpect(status().isInternalServerError());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ziper.auth_codes", Integer.class)).isOne();
	}

	private void failVerify(UUID flowId, int times) throws Exception {
		String wrongCode = wrongCodeFor(sentCode());
		for (int attempt = 0; attempt < times; attempt++) {
			mvc.perform(post(PATH + "/" + flowId + "/verify").contentType("application/json")
					.content("{\"code\":\"" + wrongCode + "\"}"));
		}
	}

	private String wrongCodeFor(String code) {
		return code.equals("000000") ? "111111" : "000000";
	}

	private void ageFailures(int minutes) {
		jdbc.update(
				"UPDATE ziper.email_verification_failures SET last_failed_at = last_failed_at - ? * INTERVAL '1 minute', "
						+ "locked_until = locked_until - ? * INTERVAL '1 minute'",
				minutes, minutes);
	}

	private UUID issue(String email) throws Exception {
		MvcResult result = mvc
				.perform(post(PATH).contentType("application/json").content("{\"email\":\"" + email + "\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.flowId").isNotEmpty()).andReturn();
		return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).get("flowId").asText());
	}

	private String sentCode() {
		ArgumentCaptor<SimpleMailMessage> messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender, atLeastOnce()).send(messages.capture());
		String body = messages.getAllValues().getLast().getText();
		Matcher matcher = Pattern.compile("인증번호: ([0-9]{6})").matcher(body);
		assertThat(matcher.find()).isTrue();
		return matcher.group(1);
	}

	private void ageLatest(UUID flowId) {
		jdbc.update("UPDATE ziper.auth_codes SET requested_at = requested_at - INTERVAL '4 minutes', "
				+ "expired_at = expired_at - INTERVAL '4 minutes' WHERE id = "
				+ "(SELECT id FROM ziper.auth_codes WHERE flow_id = ? ORDER BY id DESC LIMIT 1)", flowId);
	}
}
