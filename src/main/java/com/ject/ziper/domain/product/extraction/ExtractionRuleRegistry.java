package com.ject.ziper.domain.product.extraction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.http.ETag;
import org.springframework.stereotype.Component;

@Component
public class ExtractionRuleRegistry {
	public static final String CURRENT_VERSION = "2026-11-21.1";

	// 상품 경로 패턴은 클라이언트에 그대로 배포되므로 경로 시작(^)과 상품 번호의 끝((?:/|$))
	// 경계를 패턴 자체에 포함한다. 서버도 별도 경계 검사 없이 같은 패턴만으로 판정한다.

	private static final List<ShopExtractionRule> DEFAULT_SHOPS = List.of(
			new ShopExtractionRule("무신사",
					List.of("musinsa.com", "www.musinsa.com", "m.musinsa.com", "store.musinsa.com"),
					"^/(?:products|app/goods)/(\\d+)(?:/|$)", "https://www.musinsa.com/products/{1}",
					new ExtractionFieldRules(List.of("meta[property='og:title']@content", "ldjson:name", "title"),
							List.of("meta[property='product:price:amount']@content", "ldjson:offers.price"),
							List.of("meta[property='og:image']@content", "ldjson:image[0]"),
							List.of("ldjson:offers.availability")),
					List.of("image.msscdn.net"), ImageStrategy.SERVER_MIRROR),
			new ShopExtractionRule("에이블리", List.of("a-bly.com", "www.a-bly.com", "m.a-bly.com"),
					"^/goods/(\\d+)(?:/|$)", "https://m.a-bly.com/goods/{1}",
					new ExtractionFieldRules(List.of("meta[property='og:title']@content", "ldjson:name", "title"),
							List.of("meta[property='product:price:amount']@content", "ldjson:offers.price"),
							List.of("meta[property='og:image']@content", "ldjson:image[0]"),
							List.of("ldjson:offers.availability")),
					List.of("d3ha2047wt6x28.cloudfront.net"), ImageStrategy.SERVER_MIRROR),
			new ShopExtractionRule("지그재그", List.of("zigzag.kr", "www.zigzag.kr", "s.zigzag.kr", "store.zigzag.kr"),
					"^/catalog/products/(\\d+)(?:/|$)", "https://zigzag.kr/catalog/products/{1}",
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
		// If-None-Match는 RFC 9110 entity-tag 목록이므로 인용부호를 인식하는 Spring 파서로 읽고
		// 약한 비교(W/ 무시)를 적용한다. 인용된 태그 내부의 쉼표나 별표는 값으로 취급한다.
		ETag current = ETag.create(etag);
		for (ETag candidate : ETag.parse(ifNoneMatchHeader)) {
			if (candidate.isWildcard() || current.compare(candidate, false)) {
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
