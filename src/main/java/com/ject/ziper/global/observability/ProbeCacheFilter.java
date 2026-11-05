package com.ject.ziper.global.observability;

import java.io.IOException;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProbeCacheFilter extends OncePerRequestFilter {
	private static final Set<String> PROBES = Set.of("/livez", "/readyz", "/actuator/health/liveness",
			"/actuator/health/readiness");

	@Value("${app.monitoring.origin:local}")
	private String origin;

	@Value("${sentry.release:local}")
	private String release;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (PROBES.contains(request.getRequestURI().substring(request.getContextPath().length()))) {
			response.setHeader("Cache-Control", "no-store");
			if (origin.matches("home|cloudrun|local")) {
				response.setHeader("X-Ziper-Origin", origin);
			}
			if (release.matches("[0-9a-f]{40}|local")) {
				response.setHeader("X-Ziper-Release", release);
			}
		}
		chain.doFilter(request, response);
	}
}
