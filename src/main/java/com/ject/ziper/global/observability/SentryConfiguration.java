package com.ject.ziper.global.observability;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.ErrorResponse;

import com.ject.ziper.global.error.BusinessException;

import io.sentry.SentryOptions;
import io.sentry.protocol.SentrySpan;
import io.sentry.protocol.Request;

@Configuration(proxyBeanMethods = false)
public class SentryConfiguration {
	@Bean
	SentryOptions.BeforeSendCallback privacyBeforeSend() {
		return (event, hint) -> {
			if (event.getThrowable() instanceof BusinessException) {
				return null;
			}
			if (event.getThrowable() instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
				return null;
			}
			event.setUser(null);
			redactRequest(event.getRequest());
			return event;
		};
	}

	@Bean
	SentryOptions.TracesSamplerCallback healthTracesSampler() {
		return context -> {
			var custom = context.getCustomSamplingContext();
			Object request = custom == null ? null : custom.get("request");
			String url = request instanceof HttpServletRequest servlet
					? servlet.getRequestURI()
					: request instanceof Request sentry ? sentry.getUrl() : null;
			return isHealth(url) || isHealth(context.getTransactionContext().getName()) ? 0.0 : null;
		};
	}

	@Bean
	SentryOptions.BeforeSendTransactionCallback tracePrivacy() {
		return (transaction, hint) -> {
			transaction.setUser(null);
			redactRequest(transaction.getRequest());
			transaction.getSpans().replaceAll(span -> span.getOp().startsWith("db.") ? safeQuerySpan(span) : span);
			return transaction;
		};
	}

	private static void redactRequest(Request request) {
		if (request == null) {
			return;
		}
		request.setData(null);
		request.setCookies(null);
		request.setHeaders(null);
		request.setQueryString(null);
		request.setFragment(null);
		request.setEnvs(null);
		request.setOthers(null);
		request.setUnknown(null);
		request.setUrl(safeUrl(request.getUrl()));
	}

	private static SentrySpan safeQuerySpan(SentrySpan span) {
		String fingerprint = queryFingerprint(span.getDescription());
		return new SentrySpan(span.getStartTimestamp(), span.getTimestamp(), span.getTraceId(), span.getSpanId(),
				span.getParentSpanId(), span.getOp(), "JDBC " + fingerprint, span.getStatus(), span.getOrigin(),
				Map.of(), span.getMeasurements(), Map.of("db.query.fingerprint", fingerprint));
	}

	private static String queryFingerprint(String statement) {
		if (statement == null) {
			return "unknown";
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(statement.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 16);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("JVM must support SHA-256", exception);
		}
	}

	private static boolean isHealth(String value) {
		if (value == null) {
			return false;
		}
		String path = value.contains(" ") ? value.substring(value.indexOf(' ') + 1) : value;
		try {
			path = URI.create(path).getPath();
		} catch (IllegalArgumentException exception) {
			return false;
		}
		return "/livez".equals(path) || "/readyz".equals(path) || "/actuator/health".equals(path)
				|| (path != null && path.startsWith("/actuator/health/"));
	}

	private static String safeUrl(String url) {
		if (url == null) {
			return null;
		}
		try {
			URI uri = URI.create(url);
			return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
		} catch (URISyntaxException | IllegalArgumentException exception) {
			return null;
		}
	}
}
