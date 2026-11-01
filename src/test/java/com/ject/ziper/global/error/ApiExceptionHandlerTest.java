package com.ject.ziper.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.servlet.RequestDispatcher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.ject.ziper.global.observability.RequestIdFilter;

import io.sentry.Sentry;

class ApiExceptionHandlerTest {
	@Test
	void 클라이언트오류와예상하지못한실패_요청을처리하면_4xx는수집없이응답하고5xx는세부정보를숨겨한번만수집한다() throws Exception {
		var mvc = MockMvcBuilders.standaloneSetup(new Endpoints())
				.setControllerAdvice(new ApiExceptionHandler(new ProblemFactory())).build();
		try (var sentry = mockStatic(Sentry.class)) {
			mvc.perform(get("/number").param("value", "secret")).andExpect(status().isBadRequest())
					.andExpect(jsonPath("detail").value("요청 형식이 올바르지 않습니다."));
			mvc.perform(get("/number")).andExpect(status().isBadRequest());
			mvc.perform(post("/number")).andExpect(status().isMethodNotAllowed());
			mvc.perform(get("/missing")).andExpect(status().isNotFound());
			mvc.perform(get("/conflict")).andExpect(status().isConflict());
			sentry.verifyNoInteractions();
			mvc.perform(get("/failure")).andExpect(status().isInternalServerError())
					.andExpect(jsonPath("detail").value("서버 오류가 발생했습니다.")).andExpect(jsonPath("trace").doesNotExist());
			sentry.verify(() -> Sentry.withScope(any()));
			sentry.verifyNoMoreInteractions();
		}
	}

	@Test
	void 검증실패본문과깨진JSON본문과오류폴백요청_요청을처리하면_제출값과내부메시지를노출하지않고상태와요청ID를응답한다() throws Exception {
		var mvc = MockMvcBuilders.standaloneSetup(new Endpoints(), new SafeErrorController(new ProblemFactory()))
				.setControllerAdvice(new ApiExceptionHandler(new ProblemFactory())).addFilters(new RequestIdFilter())
				.build();
		for (String body : new String[]{"{\"name\":\"\"}", "{secret-token"}) {
			var result = mvc.perform(post("/validate").contentType("application/json").content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST")).andReturn()
					.getResponse();
			assertThat(result.getContentAsString()).doesNotContain("secret-token", "NotBlank", "rejectedValue");
		}
		var fallback = mvc
				.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
						.requestAttr(RequestDispatcher.ERROR_MESSAGE, "secret-token SQL"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("code").value("NOT_FOUND"))
				.andExpect(jsonPath("requestId").isNotEmpty()).andReturn().getResponse();
		assertThat(fallback.getContentAsString()).doesNotContain("secret-token", "SQL");
	}

	@RestController
	static class Endpoints {
		@PostMapping("/validate")
		void validate(@Valid @RequestBody Input input) {
		}

		record Input(@NotBlank String name) {
		}
		@GetMapping("/number")
		int number(@RequestParam int value) {
			return value;
		}

		@GetMapping("/conflict")
		void conflict() {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "secret");
		}

		@GetMapping("/failure")
		void failure() {
			throw new IllegalStateException("secret-token");
		}
	}
}
