package com.ject.ziper.domain.harnessfixture.nested;

import jakarta.servlet.http.HttpServletRequest;

public class ValidRequest {
	public String execute(HttpServletRequest request) {
		return request.getRequestURI();
	}
}
