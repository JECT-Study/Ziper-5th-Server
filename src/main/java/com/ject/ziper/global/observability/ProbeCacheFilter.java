package com.ject.ziper.global.observability;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProbeCacheFilter extends OncePerRequestFilter {
	private static final Set<String> PROBES = Set.of("/livez", "/readyz", "/actuator/health/liveness",
			"/actuator/health/readiness");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (PROBES.contains(request.getRequestURI().substring(request.getContextPath().length()))) {
			response.setHeader("Cache-Control", "no-store");
		}
		chain.doFilter(request, response);
	}
}
