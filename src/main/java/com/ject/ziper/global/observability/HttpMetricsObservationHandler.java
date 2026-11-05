package com.ject.ziper.global.observability;

import java.util.Map;
import java.util.Set;

import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.servlet.HandlerMapping;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;

final class HttpMetricsObservationHandler implements ObservationHandler<ServerRequestObservationContext> {
	private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD");
	private final MonitoringPublisher publisher;

	HttpMetricsObservationHandler(MonitoringPublisher publisher) {
		this.publisher = publisher;
	}

	@Override
	public boolean supportsContext(Observation.Context context) {
		return context instanceof ServerRequestObservationContext;
	}

	@Override
	public void onStart(ServerRequestObservationContext context) {
		context.put(HttpMetricsObservationHandler.class, System.nanoTime());
	}

	@Override
	public void onStop(ServerRequestObservationContext context) {
		String path = context.getCarrier().getRequestURI().substring(context.getCarrier().getContextPath().length());
		if (excluded(path)) {
			return;
		}
		Long started = context.get(HttpMetricsObservationHandler.class);
		if (started == null || context.getResponse() == null) {
			return;
		}
		Object matched = context.getCarrier().getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		String route = matched instanceof String pattern && !"/error".equals(pattern) ? pattern : "unmatched";
		String method = context.getCarrier().getMethod();
		var attributes = Map.<String, Object>of("method", METHODS.contains(method) ? method : "OTHER", "route", route,
				"status", Integer.toString(context.getResponse().getStatus()));
		publisher.publish("ziper.http.requests", 1, null, attributes);
		publisher.publish("ziper.http.failure", context.getResponse().getStatus() >= 500 ? 1 : 0, null, attributes);
		publisher.publish("ziper.http.duration", (System.nanoTime() - started) / 1_000_000.0, "millisecond",
				attributes);
	}

	private static boolean excluded(String path) {
		return path.equals("/livez") || path.equals("/readyz") || path.equals("/actuator")
				|| path.startsWith("/actuator/") || path.equals("/v3/api-docs") || path.startsWith("/v3/api-docs/")
				|| path.equals("/v3/api-docs.yaml") || path.equals("/swagger-ui.html")
				|| path.startsWith("/swagger-ui/");
	}
}
