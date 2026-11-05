package com.ject.ziper.global.error;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.global.observability.RequestIdFilter;

@Component
public class ProblemFactory {
	public ProblemDetail create(int status, ErrorCode code, HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), code.detail());
		problem.setProperty("code", code.name());
		problem.setProperty("requestId", RequestIdFilter.requestId(request));
		return problem;
	}

	public void write(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response, int status)
			throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setHeader("X-Request-Id", RequestIdFilter.requestId(request));
		if (status == 401) {
			response.setHeader("WWW-Authenticate", "Bearer");
		}
		mapper.writeValue(response.getOutputStream(), create(status, ErrorCode.forStatus(status), request));
	}
}
