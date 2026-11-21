package com.ject.ziper.domain.product.extraction;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ShopExtractionRule {
	private final String shop;
	private final List<String> hosts;
	private final String canonicalPattern;
	private final Pattern compiledCanonicalPattern;
	private final String canonicalTemplate;
	private final ExtractionFieldRules fields;
	private final List<String> imageHosts;
	private final ImageStrategy imageStrategy;

	public ShopExtractionRule(String shop, List<String> hosts, String canonicalPattern, String canonicalTemplate,
			ExtractionFieldRules fields, List<String> imageHosts, ImageStrategy imageStrategy) {
		this.shop = shop;
		this.hosts = hosts.stream().map(host -> host.toLowerCase(Locale.ROOT)).toList();
		this.canonicalPattern = canonicalPattern;
		this.compiledCanonicalPattern = Pattern.compile(canonicalPattern);
		this.canonicalTemplate = canonicalTemplate;
		this.fields = fields;
		this.imageHosts = imageHosts.stream().map(host -> host.toLowerCase(Locale.ROOT)).toList();
		this.imageStrategy = imageStrategy;
	}

	public String shop() {
		return shop;
	}

	public List<String> hosts() {
		return hosts;
	}

	public String canonicalPattern() {
		return canonicalPattern;
	}

	public String canonicalTemplate() {
		return canonicalTemplate;
	}

	public ExtractionFieldRules fields() {
		return fields;
	}

	public List<String> imageHosts() {
		return imageHosts;
	}

	public ImageStrategy imageStrategy() {
		return imageStrategy;
	}

	public boolean matchesHost(String normalizedHost) {
		return matchesAnyHost(normalizedHost, hosts);
	}

	public boolean matchesImageHost(String normalizedHost) {
		return matchesAnyHost(normalizedHost, imageHosts);
	}

	public Optional<String> canonicalizePath(String rawPath) {
		if (rawPath == null || rawPath.isEmpty()) {
			return Optional.empty();
		}
		Matcher matcher = compiledCanonicalPattern.matcher(rawPath);
		// 경로 시작과 상품 번호 끝 경계는 배포 패턴(canonicalPattern)에 포함되어 있다.
		if (!matcher.find()) {
			return Optional.empty();
		}
		String result = canonicalTemplate;
		for (int index = 1; index <= matcher.groupCount(); index++) {
			String captured = matcher.group(index);
			if (captured != null) {
				result = result.replace("{" + index + "}", captured);
			}
		}
		return Optional.of(result);
	}

	private static boolean matchesAnyHost(String normalizedHost, List<String> allowedHosts) {
		if (normalizedHost == null || normalizedHost.isBlank()) {
			return false;
		}
		for (String allowedHost : allowedHosts) {
			if (normalizedHost.equals(allowedHost) || normalizedHost.endsWith("." + allowedHost)) {
				return true;
			}
		}
		return false;
	}
}
