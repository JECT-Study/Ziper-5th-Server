package com.ject.ziper.global.observability;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.sentry.Sentry;
import io.sentry.metrics.SentryMetricsParameters;

final class MonitoringPublisher {
	static final Set<String> NAMES = Set.of("ziper.http.requests", "ziper.http.duration", "ziper.http.failure",
			"ziper.jvm.heap.used", "ziper.jvm.heap.max", "ziper.jvm.heap.ratio", "ziper.process.cpu",
			"ziper.jvm.threads", "ziper.jvm.gc.count", "ziper.jvm.gc.time", "ziper.db.active", "ziper.db.idle",
			"ziper.db.max", "ziper.db.pending", "ziper.db.timeouts");
	static final Set<String> ATTRIBUTES = Set.of("origin", "instance", "method", "route", "status", "pool",
			"sentry.environment", "sentry.release", "sentry.sdk.name", "sentry.sdk.version");
	private static final Logger log = LoggerFactory.getLogger(MonitoringPublisher.class);
	private final String origin;
	private final String instance = UUID.randomUUID().toString();

	MonitoringPublisher(String origin) {
		this.origin = origin;
	}

	void publish(String name, double value, String unit, Map<String, Object> attributes) {
		if (!Double.isFinite(value) || value < 0) {
			return;
		}
		var safe = new HashMap<>(attributes);
		safe.put("origin", origin);
		safe.put("instance", instance);
		var parameters = SentryMetricsParameters.create(safe);
		try {
			if (name.equals("ziper.http.duration") || name.equals("ziper.http.failure")) {
				Sentry.metrics().distribution(name, value, unit, parameters);
			} else if (Set.of("ziper.http.requests", "ziper.jvm.gc.count", "ziper.jvm.gc.time", "ziper.db.timeouts")
					.contains(name)) {
				Sentry.metrics().count(name, value, unit, parameters);
			} else {
				Sentry.metrics().gauge(name, value, unit, parameters);
			}
		} catch (RuntimeException exception) {
			log.warn("Monitoring metric could not be queued");
		}
	}
}
