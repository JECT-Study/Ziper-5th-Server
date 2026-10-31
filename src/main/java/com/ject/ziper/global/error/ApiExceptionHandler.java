package com.ject.ziper.global.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.sentry.Sentry;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		if (status.is5xxServerError()) {
			Sentry.captureException(exception);
		}
		return super.handleExceptionInternal(exception, safeProblem(status), headers, status, request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> unexpected(Exception exception, WebRequest request) {
		return handleExceptionInternal(exception, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
	}

	private ProblemDetail safeProblem(HttpStatusCode status) {
		return ProblemDetail.forStatusAndDetail(status,
				status.is5xxServerError() ? "An unexpected error occurred" : "The request could not be processed");
	}
}
