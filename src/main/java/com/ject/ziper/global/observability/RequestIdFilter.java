package com.ject.ziper.global.observability;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestIdFilter extends OncePerRequestFilter {
	public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

	public static String requestId(HttpServletRequest request) {
		Object existing = request.getAttribute(ATTRIBUTE);
		if (existing instanceof String id) {
			return id;
		}
		String id = UUID.randomUUID().toString();
		request.setAttribute(ATTRIBUTE, id);
		return id;
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String previous = MDC.get("requestId");
		String id = requestId(request);
		response.setHeader("X-Request-Id", id);
		MDC.put("requestId", id);
		try {
			chain.doFilter(request, response);
		} finally {
			if (previous == null) {
				MDC.remove("requestId");
			} else {
				MDC.put("requestId", previous);
			}
		}
	}
}
