package com.ject.ziper.global.error;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.sentry.Sentry;

class ApiExceptionHandlerTest {
	@Test
	void preservesClientStatusesWithoutCapturingAndCapturesUnexpectedFailureOnce() throws Exception {
		var mvc = MockMvcBuilders.standaloneSetup(new Endpoints()).setControllerAdvice(new ApiExceptionHandler())
				.build();
		try (var sentry = mockStatic(Sentry.class)) {
			mvc.perform(get("/number").param("value", "secret")).andExpect(status().isBadRequest())
					.andExpect(jsonPath("detail").value("The request could not be processed"));
			mvc.perform(get("/number")).andExpect(status().isBadRequest());
			mvc.perform(post("/number")).andExpect(status().isMethodNotAllowed());
			mvc.perform(get("/missing")).andExpect(status().isNotFound());
			mvc.perform(get("/conflict")).andExpect(status().isConflict());
			sentry.verifyNoInteractions();
			mvc.perform(get("/failure")).andExpect(status().isInternalServerError())
					.andExpect(jsonPath("detail").value("An unexpected error occurred"))
					.andExpect(jsonPath("trace").doesNotExist());
			sentry.verify(() -> Sentry.captureException(any(Exception.class)));
			sentry.verifyNoMoreInteractions();
		}
	}

	@RestController
	static class Endpoints {
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
