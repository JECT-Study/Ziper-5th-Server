package com.ject.ziper.domain.product.extraction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class ExtractionRuleRegistry {
	public static final String CURRENT_VERSION = "2026-11-21.1";

	private static final List<ShopExtractionRule> DEFAULT_SHOPS = List.of(
			new ShopExtractionRule("무신사",
					List.of("musinsa.com", "www.musinsa.com", "m.musinsa.com", "store.musinsa.com"),
					"/(?:products|app/goods)/(\\d+)", "https://www.musinsa.com/products/{1}",
					new ExtractionFieldRules(List.of("meta[property='og:title']@content", "ldjson:name", "title"),
							List.of("meta[property='product:price:amount']@content", "ldjson:offers.price"),
							List.of("meta[property='og:image']@content", "ldjson:image[0]"),
							List.of("ldjson:offers.availability")),
					List.of("image.msscdn.net"), ImageStrategy.SERVER_MIRROR),
			new ShopExtractionRule("에이블리", List.of("a-bly.com", "www.a-bly.com", "m.a-bly.com"), "/goods/(\\d+)",
					"https://m.a-bly.com/goods/{1}",
					new ExtractionFieldRules(List.of("meta[property='og:title']@content", "ldjson:name", "title"),
							List.of("meta[property='product:price:amount']@content", "ldjson:offers.price"),
							List.of("meta[property='og:image']@content", "ldjson:image[0]"),
							List.of("ldjson:offers.availability")),
					List.of("d3ha2047wt6x28.cloudfront.net"), ImageStrategy.SERVER_MIRROR),
			new ShopExtractionRule("지그재그", List.of("zigzag.kr", "www.zigzag.kr", "s.zigzag.kr", "store.zigzag.kr"),
					"/catalog/products/(\\d+)", "https://zigzag.kr/catalog/products/{1}",
					new ExtractionFieldRules(List.of("meta[property='og:title']@content", "ldjson:name", "title"),
							List.of("meta[property='product:price:amount']@content", "ldjson:offers.price",
									"json:__NEXT_DATA__.props.pageProps.product.final_price"),
							List.of("meta[property='og:image']@content", "ldjson:image[0]"),
							List.of("ldjson:offers.availability",
									"json:__NEXT_DATA__.props.pageProps.product.sales_status")),
					List.of("cf.product-image.s.zigzag.kr"), ImageStrategy.SERVER_MIRROR));

	private static final GenericExtractionRule DEFAULT_GENERIC = new GenericExtractionRule(
			List.of("meta[property='og:title']@content", "title"), List.of("meta[property='og:image']@content"));

	private final String version;
	private final List<ShopExtractionRule> shops;
	private final GenericExtractionRule generic;
	private final String etag;

	public ExtractionRuleRegistry() {
		this(CURRENT_VERSION, DEFAULT_SHOPS, DEFAULT_GENERIC);
	}

	ExtractionRuleRegistry(String version, List<ShopExtractionRule> shops, GenericExtractionRule generic) {
		this.version = version;
		this.shops = List.copyOf(shops);
		this.generic = generic;
		this.etag = computeStrongEtag(version, this.shops, generic);
	}

	public String version() {
		return version;
	}

	public List<ShopExtractionRule> shops() {
		return shops;
	}

	public GenericExtractionRule generic() {
		return generic;
	}

	public String etag() {
		return etag;
	}

	public Optional<ShopExtractionRule> findByHost(String normalizedHost) {
		if (normalizedHost == null || normalizedHost.isBlank()) {
			return Optional.empty();
		}
		for (ShopExtractionRule rule : shops) {
			if (rule.matchesHost(normalizedHost)) {
				return Optional.of(rule);
			}
		}
		return Optional.empty();
	}

	public Optional<ShopExtractionRule> findByShop(String shop) {
		if (shop == null || shop.isBlank()) {
			return Optional.empty();
		}
		for (ShopExtractionRule rule : shops) {
			if (rule.shop().equals(shop)) {
				return Optional.of(rule);
			}
		}
		return Optional.empty();
	}

	public boolean matchesEtag(String ifNoneMatchHeader) {
		if (ifNoneMatchHeader == null || ifNoneMatchHeader.isBlank()) {
			return false;
		}
		for (String rawCandidate : ifNoneMatchHeader.split(",")) {
			String candidate = rawCandidate.trim();
			if ("*".equals(candidate)) {
				return true;
			}
			if (candidate.startsWith("W/")) {
				candidate = candidate.substring(2).trim();
			}
			if (etag.equals(candidate)) {
				return true;
			}
		}
		return false;
	}

	private static String computeStrongEtag(String version, List<ShopExtractionRule> shops,
			GenericExtractionRule generic) {
		StringBuilder fingerprint = new StringBuilder();
		fingerprint.append(version).append('|');
		for (ShopExtractionRule rule : shops) {
			fingerprint.append(rule.shop()).append('|').append(rule.hosts()).append('|').append(rule.canonicalPattern())
					.append('|').append(rule.canonicalTemplate()).append('|').append(rule.fields()).append('|')
					.append(rule.imageHosts()).append('|').append(rule.imageStrategy()).append(';');
		}
		fingerprint.append(generic);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashed = digest.digest(fingerprint.toString().getBytes(StandardCharsets.UTF_8));
			String shortHash = HexFormat.of().formatHex(hashed).substring(0, 12);
			return "\"" + version + "-" + shortHash + "\"";
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm not available", exception);
		}
	}
}
