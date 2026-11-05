package com.ject.ziper.global.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.sentry.Hint;
import io.sentry.SentryAttributeType;
import io.sentry.SentryLogEventAttributeValue;
import io.sentry.SentryMetricsEvent;
import io.sentry.protocol.SentryId;

class MonitoringConfigurationTest {
	@Test
	void 개인정보가섞인지표_전송필터를통과하면_허용한속성만남고비활성시차단한다() {
		var event = new SentryMetricsEvent(new SentryId(), 1.0, "ziper.http.requests", "counter", 1.0);
		event.setAttribute("user.email", new SentryLogEventAttributeValue(SentryAttributeType.STRING, "private"));
		event.setAttribute("route", new SentryLogEventAttributeValue(SentryAttributeType.STRING, "/items/{id}"));
		var config = new MonitoringConfiguration();
		assertThat(config.metricPrivacy(true).execute(event, new Hint()).getAttributes()).containsOnlyKeys("route");
		assertThat(config.metricPrivacy(false).execute(event, new Hint())).isNull();
		event.setName("unknown");
		assertThat(config.metricPrivacy(true).execute(event, new Hint())).isNull();
	}

	@Test
	void 잘못된활성화설정_수집기를생성하면_비밀값없이시작을거부한다() {
		var config = new MonitoringConfiguration.Enabled();
		assertThatThrownBy(() -> config.monitoringPublisher(false, "", "local"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> config.monitoringPublisher(true, "https://public@example.com/1", "cloudrun"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 누적GC지표_두번수집하고초기화하면_증가량을초단위로전송하고음수를보내지않는다() {
		var registry = new SimpleMeterRegistry();
		try {
			var publisher = mock(MonitoringPublisher.class);
			var collector = new ResourceMetricsCollector(registry, publisher);
			Timer timer = registry.timer("jvm.gc.pause");
			timer.record(Duration.ofMillis(10));
			collector.collect();
			timer.record(Duration.ofMillis(30));
			collector.collect();
			verify(publisher).publish("ziper.jvm.gc.count", 1, null, Map.of());
			verify(publisher).publish(eq("ziper.jvm.gc.time"), eq(0.03), eq("second"), any());
			registry.remove(timer);
			registry.timer("jvm.gc.pause");
			collector.collect();
			verify(publisher, never()).publish(eq("ziper.jvm.gc.count"), eq(-2.0), any(), any());
		} finally {
			registry.close();
		}
	}
}
