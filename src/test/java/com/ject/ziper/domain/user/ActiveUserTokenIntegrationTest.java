package com.ject.ziper.domain.user;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Import(ActiveUserTokenIntegrationTest.Endpoints.class)
class ActiveUserTokenIntegrationTest extends PostgresTestSupport {
	private static final String PROTECTED = "/test/jec92/protected";

	@Autowired
	private MockMvc mvc;
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private JwtTokenProvider tokens;

	@BeforeEach
	@AfterEach
	void clear() {
		jdbc.update("DELETE FROM ziper.users");
	}

	@Test
	void 활성회원의Access토큰이주어졌을때_보호API를요청하면_기존대로통과한다() throws Exception {
		long id = insertUser("active@example.com", "활성");

		mvc.perform(get(PROTECTED).header("Authorization", "Bearer " + tokens.issueAccessToken(id)))
				.andExpect(status().isOk());
	}

	@Test
	void 삭제된회원의유효한Access토큰이주어졌을때_보호API를요청하면_500이아닌401을응답한다() throws Exception {
		long id = insertUser("gone@example.com", "삭제");
		String token = tokens.issueAccessToken(id);
		jdbc.update("DELETE FROM ziper.users WHERE id = ?", id);

		mvc.perform(get(PROTECTED).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"))
				.andExpect(jsonPath("code").value("UNAUTHORIZED"));
		mvc.perform(post(PROTECTED).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
	}

	@Test
	void 존재한적없는회원ID의토큰이주어졌을때_보호API를요청하면_401을응답한다() throws Exception {
		mvc.perform(get(PROTECTED).header("Authorization", "Bearer " + tokens.issueAccessToken(987654321L)))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("code").value("UNAUTHORIZED"));
	}

	@Test
	void 탈퇴한회원의만료전Access토큰이주어졌을때_보호API를요청하면_401을응답한다() throws Exception {
		long id = insertUser("left@example.com", "탈퇴");
		String token = tokens.issueAccessToken(id);
		mvc.perform(get(PROTECTED).header("Authorization", "Bearer " + token)).andExpect(status().isOk());

		jdbc.update("UPDATE ziper.users SET status = 'WITHDRAWN' WHERE id = ?", id);

		mvc.perform(get(PROTECTED).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("code").value("UNAUTHORIZED"));
	}

	private long insertUser(String email, String nickname) {
		return jdbc.queryForObject("INSERT INTO ziper.users (email, nickname) VALUES (?, ?) RETURNING id", Long.class,
				email, nickname);
	}

	@RestController
	static class Endpoints {
		@GetMapping(PROTECTED)
		String read() {
			return "ok";
		}

		@PostMapping(PROTECTED)
		String write() {
			return "ok";
		}
	}
}
