package com.ject.ziper.domain.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.ject.ziper.global.persistence.PostgresTestSupport;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class NicknameAvailabilityIntegrationTest extends PostgresTestSupport {
	private static final String PATH = "/api/v1/users/nickname/availability";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void insertRegisteredUser() {
		jdbc.update("INSERT INTO ziper.users (email, nickname) VALUES (?, ?)", "taken@example.com", "집퍼123");
	}

	@AfterEach
	void deleteUsers() {
		jdbc.update("DELETE FROM ziper.users");
	}

	@ParameterizedTest
	@ValueSource(strings = {"집퍼", "ziper", "Ab", "가나다라마바사아자차", "닉네임Nick7", "1234567890", "집퍼1234"})
	void 인증없이규칙에맞고사용하지않은닉네임_중복검사하면_사용가능으로응답한다(String nickname) throws Exception {
		mvc.perform(get(PATH).param("nickname", nickname)).andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("available").value(true));
	}

	@Test
	void 이미가입한사용자의닉네임_중복검사하면_409와중복오류코드를응답한다() throws Exception {
		mvc.perform(get(PATH).param("nickname", "집퍼123")).andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("code").value("DUPLICATE_NICKNAME"))
				.andExpect(jsonPath("detail").value("이미 사용 중인 닉네임입니다.")).andExpect(jsonPath("requestId").isNotEmpty());
	}

	@Test
	void 대소문자만다른기존닉네임_중복검사하면_다른닉네임으로보고사용가능으로응답한다() throws Exception {
		jdbc.update("INSERT INTO ziper.users (email, nickname) VALUES (?, ?)", "case@example.com", "Ziper");
		mvc.perform(get(PATH).param("nickname", "ziper")).andExpect(status().isOk())
				.andExpect(jsonPath("available").value(true));
		mvc.perform(get(PATH).param("nickname", "Ziper")).andExpect(status().isConflict())
				.andExpect(jsonPath("code").value("DUPLICATE_NICKNAME"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "집", "가나다라마바사아자차카", "abcdefghijk", "집 퍼", " 집퍼", "집퍼!", "zip_er", "ㄱㄴ", "집ㅏ", "ｚｉｐ",
			"١٢٣"})
	void 규칙에맞지않는닉네임_중복검사하면_400과형식오류코드를응답한다(String nickname) throws Exception {
		mvc.perform(get(PATH).param("nickname", nickname)).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("code").value("INVALID_NICKNAME"))
				.andExpect(jsonPath("detail").value("닉네임은 한글, 영문, 숫자만 사용해 2~10자로 입력해 주세요."));
	}

	@Test
	void 닉네임파라미터가없는요청_중복검사하면_400과형식오류코드를응답한다() throws Exception {
		mvc.perform(get(PATH)).andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_NICKNAME"));
	}
}
