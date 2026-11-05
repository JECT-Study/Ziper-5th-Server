package com.ject.ziper.global.observability;

import java.net.URI;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import io.micrometer.core.instrument.MeterRegistry;
import io.sentry.SentryOptions;

@Configuration(proxyBeanMethods = false)
public class MonitoringConfiguration {
	@Bean
	SentryOptions.Metrics.BeforeSendMetricCallback metricPrivacy(
			@Value("${app.monitoring.metrics-enabled:false}") boolean enabled) {
		return (metric, hint) -> {
			if (!enabled || !MonitoringPublisher.NAMES.contains(metric.getName())) {
				return null;
			}
			if (metric.getAttributes() != null) {
				metric.getAttributes().keySet().removeIf(key -> !MonitoringPublisher.ATTRIBUTES.contains(key));
			}
			return metric;
		};
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "app.monitoring.metrics-enabled", havingValue = "true")
	@EnableScheduling
	static class Enabled {
		@Bean
		MonitoringPublisher monitoringPublisher(@Value("${sentry.enabled:false}") boolean enabled,
				@Value("${sentry.dsn:}") String dsn, @Value("${app.monitoring.origin:local}") String origin) {
			if (!enabled || dsn.isBlank()) {
				throw new IllegalArgumentException("Monitoring requires enabled Sentry and a DSN");
			}
			URI uri;
			try {
				uri = URI.create(dsn);
			} catch (IllegalArgumentException exception) {
				throw new IllegalArgumentException("Monitoring requires a valid HTTPS Sentry DSN");
			}
			if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() == null) {
				throw new IllegalArgumentException("Monitoring requires a valid HTTPS Sentry DSN");
			}
			if (!"local".equals(origin) && !"home".equals(origin)) {
				throw new IllegalArgumentException("Monitoring origin must be local or home");
			}
			return new MonitoringPublisher(origin);
		}

		@Bean
		HttpMetricsObservationHandler httpMetricsObservationHandler(MonitoringPublisher publisher) {
			return new HttpMetricsObservationHandler(publisher);
		}

		@Bean
		ResourceMetricsCollector resourceMetricsCollector(MeterRegistry registry, MonitoringPublisher publisher) {
			return new ResourceMetricsCollector(registry, publisher);
		}
	}
}
