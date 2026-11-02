package com.ject.ziper.global.error;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.ject.ziper.global.observability.RequestIdFilter;

import io.sentry.Sentry;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
	private final ProblemFactory problems;

	public ApiExceptionHandler(ProblemFactory problems) {
		this.problems = problems;
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		HttpServletRequest servlet = ((ServletWebRequest) request).getRequest();
		if (status.is5xxServerError()) {
			log.error("서버 오류 requestId={}", RequestIdFilter.requestId(servlet), exception);
			Sentry.withScope(scope -> {
				scope.setTag("requestId", RequestIdFilter.requestId(servlet));
				Sentry.captureException(exception);
			});
		}
		return new ResponseEntity<>(problems.create(status.value(), ErrorCode.forStatus(status.value()), servlet),
				headers, status);
	}

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<Object> business(BusinessException exception, HttpServletRequest request) {
		ErrorCode code = exception.errorCode();
		return ResponseEntity.status(code.status()).body(problems.create(code.status().value(), code, request));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> unexpected(Exception exception, WebRequest request) {
		return handleExceptionInternal(exception, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
	}
}
