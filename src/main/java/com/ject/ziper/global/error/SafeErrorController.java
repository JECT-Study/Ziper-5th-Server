package com.ject.ziper.global.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SafeErrorController implements ErrorController {
	private final ProblemFactory problems;

	public SafeErrorController(ProblemFactory problems) {
		this.problems = problems;
	}

	@RequestMapping("/error")
	ResponseEntity<Object> error(HttpServletRequest request) {
		Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
		int status = value instanceof Integer code && code >= 400 && code <= 599 ? code : 500;
		return ResponseEntity.status(status).body(problems.create(status, ErrorCode.forStatus(status), request));
	}
}
