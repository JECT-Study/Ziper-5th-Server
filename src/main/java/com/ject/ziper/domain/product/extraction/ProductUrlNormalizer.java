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

		// 지원몰 판정은 호스트와 배포 상품 ID 패턴이 모두 일치할 때만 한다(기본 포트 한정).
		// 패턴 불일치는 수기 입력 흐름을 위해 supported=false, shop=null로 반환한다.
		if (matchedShop.isPresent() && parsed.port() == -1) {
			ShopExtractionRule rule = matchedShop.get();
			Optional<String> canonicalized = rule.canonicalizePath(parsed.path());
			if (canonicalized.isPresent()) {
				return new NormalizedProductUrl(validateMaxLength(canonicalized.get()), rule.shop(), true);
			}
		}

		// 범위 제한: 지원몰이 아닌 URL은 의미를 바꾸지 않는 정규화(호스트 소문자·기본 포트·후행 점,
		// 점 세그먼트, 퍼센트 인코딩 대소문자와 비예약 문자 디코딩, 추적 파라미터·프래그먼트 제거)만 한다.
		// http->https 강제와 www. 제거는 product_url이 원본 이동 링크로도 쓰이므로 적용하지 않는다.
		// m./www. 통일은 지원몰 canonicalTemplate에서만 일어난다.
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
		String rawPath = uri.getRawPath() == null ? "" : normalizePercentEncoding(uri.getRawPath());
		String path;
		try {
			// 비예약 문자 디코딩으로 드러난 점 세그먼트(%2e)까지 해석하기 위해 디코딩 후 경로를 정규화한다.
			path = new URI("http://h" + rawPath).normalize().getRawPath();
		} catch (URISyntaxException exception) {
			return Optional.empty();
		}
		if (path == null) {
			path = "";
		}
		String rawQuery = uri.getRawQuery() == null ? null : normalizePercentEncoding(uri.getRawQuery());
		return Optional.of(new ParsedUri(scheme, host, port, path, rawQuery));
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
		// 키 기준 안정 정렬: 같은 키의 값 순서는 보존한다(첫 값/마지막 값을 쓰는 쇼핑몰에서 다른 상품이 같은 URL이 되는 것을 막는다).
		kept.sort(Comparator.comparing(QueryEntry::lowerKey));
		StringBuilder joined = new StringBuilder();
		for (int index = 0; index < kept.size(); index++) {
			if (index > 0) {
				joined.append('&');
			}
			joined.append(kept.get(index).rawSegment());
		}
		return joined.toString();
	}

	/**
	 * RFC 3986 6.2.2 구문 기반 정규화: 퍼센트 인코딩 16진수를 대문자로 통일하고 비예약 문자(ALPHA, DIGIT, '-',
	 * '.', '_', '~')의 인코딩은 디코딩한다. 예약 문자(%2F 등)는 의미가 달라지므로 인코딩을 유지한다.
	 */
	private static String normalizePercentEncoding(String raw) {
		StringBuilder result = new StringBuilder(raw.length());
		for (int index = 0; index < raw.length(); index++) {
			char current = raw.charAt(index);
			if (current == '%' && index + 2 < raw.length() && isHex(raw.charAt(index + 1))
					&& isHex(raw.charAt(index + 2))) {
				int decoded = Integer.parseInt(raw.substring(index + 1, index + 3), 16);
				if (isUnreserved(decoded)) {
					result.append((char) decoded);
				} else {
					result.append('%').append(Character.toUpperCase(raw.charAt(index + 1)))
							.append(Character.toUpperCase(raw.charAt(index + 2)));
				}
				index += 2;
			} else {
				result.append(current);
			}
		}
		return result.toString();
	}

	private static boolean isHex(char value) {
		return Character.digit(value, 16) >= 0 && value < 128;
	}

	private static boolean isUnreserved(int value) {
		return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z') || (value >= '0' && value <= '9')
				|| value == '-' || value == '.' || value == '_' || value == '~';
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
