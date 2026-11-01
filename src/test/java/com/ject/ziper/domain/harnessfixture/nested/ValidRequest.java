package com.ject.ziper.domain.harnessfixture.nested;

public class ValidRequest {
	public String execute(jakarta.servlet.http.HttpServletRequest request) {
		return request.getRequestURI();
	}
}
