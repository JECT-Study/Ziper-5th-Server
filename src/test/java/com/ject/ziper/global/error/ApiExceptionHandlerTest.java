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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.sentry.Sentry;

class ApiExceptionHandlerTest {
	@Test
	void preservesClientStatusesWithoutCapturingAndCapturesUnexpectedFailureOnce() throws Exception {
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
	void validationMalformedBodyAndErrorFallbackNeverExposeSubmittedValues() throws Exception {
		var mvc = MockMvcBuilders.standaloneSetup(new Endpoints(), new SafeErrorController(new ProblemFactory()))
				.setControllerAdvice(new ApiExceptionHandler(new ProblemFactory()))
				.addFilters(new com.ject.ziper.global.observability.RequestIdFilter()).build();
		for (String body : new String[]{"{\"name\":\"\"}", "{secret-token"}) {
			var result = mvc.perform(post("/validate").contentType("application/json").content(body))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST")).andReturn()
					.getResponse();
			assertThat(result.getContentAsString()).doesNotContain("secret-token", "NotBlank", "rejectedValue");
		}
		mvc.perform(get("/error").requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 404)
				.requestAttr(jakarta.servlet.RequestDispatcher.ERROR_MESSAGE, "secret-token SQL"))
				.andExpect(status().isNotFound()).andExpect(jsonPath("code").value("NOT_FOUND"))
				.andExpect(jsonPath("requestId").isNotEmpty());
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
