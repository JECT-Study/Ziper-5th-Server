package com.ject.ziper.domain.product.extraction;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

@Component
public class ProductUrlNormalizer {
	public static final int MAX_URL_LENGTH = 2048;

	private static final Set<String> TRACKING_QUERY_PARAMS = Set.of("fbclid", "gclid", "yclid", "mc_cid", "mc_eid",
			"igshid", "ref", "ref_src", "share_source", "spm");

	private final ExtractionRuleRegistry registry;

	public ProductUrlNormalizer(ExtractionRuleRegistry registry) {
		this.registry = registry;
	}

	public NormalizedProductUrl normalize(String rawUrl) {
		ParsedUri parsed = parseHttpUri(rawUrl, false);
		Optional<ShopExtractionRule> matchedShop = registry.findByHost(parsed.host());

		if (matchedShop.isPresent()) {
			ShopExtractionRule rule = matchedShop.get();
			if (parsed.port() == -1) {
				Optional<String> canonicalized = rule.canonicalizePath(parsed.path());
				if (canonicalized.isPresent()) {
					return new NormalizedProductUrl(validateMaxLength(canonicalized.get()), rule.shop(), true);
				}
			}
			String genericNormalized = buildNormalizedUrl(parsed);
			return new NormalizedProductUrl(validateMaxLength(genericNormalized), rule.shop(), true);
		}

		String genericNormalized = buildNormalizedUrl(parsed);
		return new NormalizedProductUrl(validateMaxLength(genericNormalized), null, false);
	}

	public boolean isAllowedImageHost(String shop, String imageUrl) {
		Optional<ShopExtractionRule> rule = registry.findByShop(shop);
		if (rule.isEmpty()) {
			return false;
		}
		Optional<ParsedUri> parsed = tryParseHttpUri(imageUrl, true);
		if (parsed.isEmpty()) {
			return false;
		}
		ParsedUri uri = parsed.get();
		if (uri.port() != -1) {
			return false;
		}
		return rule.get().matchesImageHost(uri.host());
	}

	public boolean isValidHttpsUrl(String rawUrl) {
		return tryParseHttpUri(rawUrl, true).isPresent();
	}

	private ParsedUri parseHttpUri(String rawUrl, boolean httpsOnly) {
		return tryParseHttpUri(rawUrl, httpsOnly)
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_PRODUCT_URL));
	}

	private Optional<ParsedUri> tryParseHttpUri(String rawUrl, boolean httpsOnly) {
		if (rawUrl == null) {
			return Optional.empty();
		}
		String trimmed = rawUrl.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_URL_LENGTH || containsControlCharacter(trimmed)) {
			return Optional.empty();
		}
		URI uri;
		try {
			uri = new URI(trimmed);
		} catch (URISyntaxException exception) {
			return Optional.empty();
		}
		if (uri.getRawUserInfo() != null) {
			return Optional.empty();
		}
		String rawScheme = uri.getScheme();
		if (rawScheme == null) {
			return Optional.empty();
		}
		String scheme = rawScheme.toLowerCase(Locale.ROOT);
		if (httpsOnly ? !"https".equals(scheme) : (!"http".equals(scheme) && !"https".equals(scheme))) {
			return Optional.empty();
		}
		String rawHost = uri.getHost();
		if (rawHost == null || rawHost.isBlank()) {
			return Optional.empty();
		}
		String host = stripTrailingDots(rawHost.toLowerCase(Locale.ROOT));
		if (host.isEmpty()) {
			return Optional.empty();
		}
		int port = uri.getPort();
		if (port == 0 || port > 65535) {
			return Optional.empty();
		}
		if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
			port = -1;
		}
		URI normalizedUri = uri.normalize();
		String path = normalizedUri.getRawPath();
		if (path == null) {
			path = "";
		}
		return Optional.of(new ParsedUri(scheme, host, port, path, uri.getRawQuery()));
	}

	private static String buildNormalizedUrl(ParsedUri parsed) {
		StringBuilder url = new StringBuilder();
		url.append(parsed.scheme()).append("://").append(parsed.host());
		if (parsed.port() != -1) {
			url.append(':').append(parsed.port());
		}
		url.append(parsed.path());
		String cleanedQuery = cleanQuery(parsed.rawQuery());
		if (!cleanedQuery.isEmpty()) {
			url.append('?').append(cleanedQuery);
		}
		return url.toString();
	}

	private static String cleanQuery(String rawQuery) {
		if (rawQuery == null || rawQuery.isEmpty()) {
			return "";
		}
		List<QueryEntry> kept = new ArrayList<>();
		for (String segment : rawQuery.split("&")) {
			if (segment.isEmpty()) {
				continue;
			}
			int equalsIndex = segment.indexOf('=');
			String rawKey = equalsIndex >= 0 ? segment.substring(0, equalsIndex) : segment;
			String lowerKey = rawKey.toLowerCase(Locale.ROOT);
			if (isTrackingParam(lowerKey)) {
				continue;
			}
			kept.add(new QueryEntry(lowerKey, segment));
		}
		if (kept.isEmpty()) {
			return "";
		}
		kept.sort(Comparator.comparing(QueryEntry::lowerKey).thenComparing(QueryEntry::rawSegment));
		StringBuilder joined = new StringBuilder();
		for (int index = 0; index < kept.size(); index++) {
			if (index > 0) {
				joined.append('&');
			}
			joined.append(kept.get(index).rawSegment());
		}
		return joined.toString();
	}

	private static boolean isTrackingParam(String lowerKey) {
		return lowerKey.startsWith("utm_") || TRACKING_QUERY_PARAMS.contains(lowerKey);
	}

	private static boolean containsControlCharacter(String value) {
		for (int index = 0; index < value.length(); index++) {
			if (Character.isISOControl(value.charAt(index))) {
				return true;
			}
		}
		return false;
	}

	private static String stripTrailingDots(String host) {
		int end = host.length();
		while (end > 0 && host.charAt(end - 1) == '.') {
			end--;
		}
		return host.substring(0, end);
	}

	private static String validateMaxLength(String normalizedUrl) {
		if (normalizedUrl.length() > MAX_URL_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_PRODUCT_URL);
		}
		return normalizedUrl;
	}

	private record ParsedUri(String scheme, String host, int port, String path, String rawQuery) {
	}

	private record QueryEntry(String lowerKey, String rawSegment) {
	}
}
