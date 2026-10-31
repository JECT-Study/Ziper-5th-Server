package com.ject.ziper.global.observability;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.ErrorResponse;

import io.sentry.SentryOptions;
import io.sentry.protocol.Request;

@Configuration(proxyBeanMethods = false)
public class SentryConfiguration {
	@Bean
	SentryOptions.BeforeSendCallback privacyBeforeSend() {
		return (event, hint) -> {
			if (event.getThrowable() instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
				return null;
			}
			event.setUser(null);
			Request request = event.getRequest();
			if (request != null) {
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
		} catch (java.net.URISyntaxException | IllegalArgumentException exception) {
			return null;
		}
	}
}
