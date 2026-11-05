package com.ject.ziper.global.observability;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Timer;

final class ResourceMetricsCollector {
	private static final Map<String, String> GAUGES = Map.of("process.cpu.usage", "ziper.process.cpu",
			"jvm.threads.live", "ziper.jvm.threads", "hikaricp.connections.active", "ziper.db.active",
			"hikaricp.connections.idle", "ziper.db.idle", "hikaricp.connections.max", "ziper.db.max",
			"hikaricp.connections.pending", "ziper.db.pending");
	private final MeterRegistry registry;
	private final MonitoringPublisher publisher;
	private final Map<Meter.Id, Map<Statistic, Double>> previous = new HashMap<>();

	ResourceMetricsCollector(MeterRegistry registry, MonitoringPublisher publisher) {
		this.registry = registry;
		this.publisher = publisher;
	}

	@Scheduled(fixedDelay = 60000, initialDelay = 60000)
	synchronized void collect() {
		var heap = registry.find("jvm.memory.used").tag("area", "heap").gauges();
		double used = heap.stream().mapToDouble(Gauge::value).sum();
		double max = registry.find("jvm.memory.max").tag("area", "heap").gauges().stream().mapToDouble(Gauge::value)
				.filter(value -> value > 0).sum();
		if (!heap.isEmpty()) {
			publisher.publish("ziper.jvm.heap.used", used, "byte", Map.of());
		}
		if (max > 0) {
			publisher.publish("ziper.jvm.heap.max", max, "byte", Map.of());
			publisher.publish("ziper.jvm.heap.ratio", used / max, null, Map.of());
		}
		for (Meter meter : registry.getMeters()) {
			String name = meter.getId().getName();
			String pool = meter.getId().getTag("pool");
			Map<String, Object> attributes = pool == null ? Map.of() : Map.of("pool", pool);
			if (meter instanceof Gauge gauge && GAUGES.containsKey(name)) {
				publisher.publish(GAUGES.get(name), gauge.value(), null, attributes);
			} else if (name.equals("jvm.gc.pause") || name.equals("hikaricp.connections.timeout")) {
				var current = new HashMap<Statistic, Double>();
				var baseline = previous.get(meter.getId());
				for (var measurement : meter.measure()) {
					Statistic statistic = measurement.getStatistic();
					if (statistic != Statistic.COUNT && statistic != Statistic.TOTAL_TIME) {
						continue;
					}
					double value = statistic == Statistic.TOTAL_TIME && meter instanceof Timer timer
							? timer.totalTime(TimeUnit.SECONDS)
							: measurement.getValue();
					if (!Double.isFinite(value) || value < 0) {
						continue;
					}
					current.put(statistic, value);
					if (baseline != null && baseline.containsKey(statistic) && value >= baseline.get(statistic)) {
						String target = name.equals("hikaricp.connections.timeout")
								? "ziper.db.timeouts"
								: statistic == Statistic.COUNT ? "ziper.jvm.gc.count" : "ziper.jvm.gc.time";
						publisher.publish(target, value - baseline.get(statistic),
								statistic == Statistic.TOTAL_TIME ? "second" : null, attributes);
					}
				}
				previous.put(meter.getId(), current);
			}
		}
		previous.keySet().removeIf(id -> registry.getMeters().stream().noneMatch(meter -> meter.getId().equals(id)));
	}
}
