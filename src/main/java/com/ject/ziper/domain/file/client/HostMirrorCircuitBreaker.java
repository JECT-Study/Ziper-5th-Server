package com.ject.ziper.domain.file.client;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ject.ziper.global.storage.StorageProperties;

@Component
public class HostMirrorCircuitBreaker {
	private static final long PERMIT_ACQUIRE_TIMEOUT_SECONDS = 15L;

	private final StorageProperties properties;
	private final Clock clock;
	private final ConcurrentMap<String, HostState> states = new ConcurrentHashMap<>();

	@Autowired
	public HostMirrorCircuitBreaker(StorageProperties properties) {
		this(properties, Clock.systemUTC());
	}

	public HostMirrorCircuitBreaker(StorageProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	public boolean isOpen(String host) {
		String key = normalizeHost(host);
		HostState state = states.get(key);
		if (state == null) {
			return false;
		}
		Instant openUntil = state.openUntil.get();
		if (openUntil == null) {
			return false;
		}
		if (clock.instant().isBefore(openUntil)) {
			return true;
		}
		if (state.openUntil.compareAndSet(openUntil, null)) {
			state.consecutiveRateLimitErrors.set(0);
		}
		return false;
	}

	public void recordSuccess(String host) {
		HostState state = states.get(normalizeHost(host));
		if (state != null) {
			state.consecutiveRateLimitErrors.set(0);
			state.openUntil.set(null);
		}
	}

	public boolean recordRateLimited(String host) {
		HostState state = stateFor(host);
		int failures = state.consecutiveRateLimitErrors.incrementAndGet();
		if (failures >= properties.mirrorCircuitFailureThreshold()) {
			state.openUntil.set(clock.instant().plusSeconds(properties.mirrorCircuitOpenSeconds()));
			return true;
		}
		return false;
	}

	public <T> T executeWithPermit(String host, Supplier<T> action, Supplier<T> onCircuitOpen,
			Supplier<T> onRateLimitInterrupted) {
		if (isOpen(host)) {
			return onCircuitOpen.get();
		}
		HostState state = stateFor(host);
		boolean acquired;
		try {
			acquired = state.semaphore.tryAcquire(PERMIT_ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			return onRateLimitInterrupted.get();
		}
		if (!acquired) {
			return onRateLimitInterrupted.get();
		}
		try {
			if (isOpen(host)) {
				return onCircuitOpen.get();
			}
			if (!enforceMinInterval(state)) {
				return onRateLimitInterrupted.get();
			}
			return action.get();
		} finally {
			state.semaphore.release();
		}
	}

	public void reset() {
		states.clear();
	}

	private boolean enforceMinInterval(HostState state) {
		long minIntervalMs = properties.mirrorHostMinIntervalMs();
		if (minIntervalMs <= 0) {
			return true;
		}
		long waitMs;
		synchronized (state.rateLock) {
			long nowMs = clock.millis();
			long nextAllowedMs = state.lastRequestAtMs.get() + minIntervalMs;
			waitMs = Math.max(0L, nextAllowedMs - nowMs);
			state.lastRequestAtMs.set(nowMs + waitMs);
		}
		if (waitMs > 0) {
			try {
				Thread.sleep(waitMs);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return true;
	}

	private HostState stateFor(String host) {
		return states.computeIfAbsent(normalizeHost(host),
				ignored -> new HostState(properties.mirrorHostMaxConcurrency()));
	}

	private static String normalizeHost(String host) {
		return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
	}

	private static final class HostState {
		private final Semaphore semaphore;
		private final AtomicInteger consecutiveRateLimitErrors = new AtomicInteger(0);
		private final AtomicReference<Instant> openUntil = new AtomicReference<>();
		private final AtomicLong lastRequestAtMs = new AtomicLong(0L);
		private final Object rateLock = new Object();

		private HostState(int maxConcurrency) {
			this.semaphore = new Semaphore(Math.max(1, maxConcurrency), true);
		}
	}
}
