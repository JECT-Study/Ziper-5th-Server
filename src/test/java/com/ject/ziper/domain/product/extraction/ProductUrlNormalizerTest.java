package com.ject.ziper.domain.product.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

class ProductUrlNormalizerTest {
	private ExtractionRuleRegistry registry;
	private ProductUrlNormalizer normalizer;

	@BeforeEach
	void setUp() {
		registry = new ExtractionRuleRegistry();
		normalizer = new ProductUrlNormalizer(registry);
	}

	@ParameterizedTest
	@MethodSource("supportedShopUrls")
	void 일차지원쇼핑몰상품URL이주어지면_정규화를실행하면_표준URL과쇼핑몰을반환한다(String inputUrl, String expectedCanonicalUrl, String expectedShop) {
		NormalizedProductUrl result = normalizer.normalize(inputUrl);

		assertThat(result.canonicalUrl()).isEqualTo(expectedCanonicalUrl);
		assertThat(result.shop()).isEqualTo(expectedShop);
		assertThat(result.supported()).isTrue();
	}

	static Stream<Arguments> supportedShopUrls() {
		return Stream.of(
				Arguments.of("https://m.musinsa.com/products/1234567?utm_source=kakao&fbclid=abc#reviews",
						"https://www.musinsa.com/products/1234567", "무신사"),
				Arguments.of("HTTPS://WWW.MUSINSA.COM:443/products/1234567/",
						"https://www.musinsa.com/products/1234567", "무신사"),
				Arguments.of("https://store.musinsa.com/app/goods/987654/0?loc=goods_rank&utm_medium=share",
						"https://www.musinsa.com/products/987654", "무신사"),
				Arguments.of("http://musinsa.com:80/products/555555", "https://www.musinsa.com/products/555555", "무신사"),
				Arguments.of("https://global.musinsa.com/products/222222?ref=app",
						"https://www.musinsa.com/products/222222", "무신사"),
				Arguments.of("https://a-bly.com/goods/123456?utm_source=share&ref=app",
						"https://m.a-bly.com/goods/123456", "에이블리"),
				Arguments.of("https://www.a-bly.com/goods/123456/", "https://m.a-bly.com/goods/123456", "에이블리"),
				Arguments.of("https://m.a-bly.com/goods/123456#detail", "https://m.a-bly.com/goods/123456", "에이블리"),
				Arguments.of("https://s.zigzag.kr/catalog/products/777888?utm_campaign=share&share_source=ios#top",
						"https://zigzag.kr/catalog/products/777888", "지그재그"),
				Arguments.of("https://store.zigzag.kr/catalog/products/777888/detail",
						"https://zigzag.kr/catalog/products/777888", "지그재그"),
				Arguments.of("https://www.zigzag.kr/catalog/products/777888",
						"https://zigzag.kr/catalog/products/777888", "지그재그"));
	}

	@Test
	void 지원쇼핑몰의비상품경로가주어지면_정규화를실행하면_지원몰로판정하고추적파라미터만제거한다() {
		NormalizedProductUrl result = normalizer
				.normalize("https://WWW.MUSINSA.COM:443/categories/item/001?page=2&utm_source=ig&sort=new#list");

		assertThat(result.canonicalUrl()).isEqualTo("https://www.musinsa.com/categories/item/001?page=2&sort=new");
		assertThat(result.shop()).isEqualTo("무신사");
		assertThat(result.supported()).isTrue();
	}

	@Test
	void 미지원쇼핑몰URL과추적파라미터가주어지면_정규화를실행하면_공통규칙으로정리하고미지원으로반환한다() {
		NormalizedProductUrl first = normalizer.normalize(
				"HTTPS://Shop.Example.COM:443/items/99?size=M&utm_source=ig&utm_medium=cpc&fbclid=1&gclid=2&yclid=3&mc_cid=4&mc_eid=5&igshid=6&ref=7&ref_src=8&share_source=9&spm=10&color=black#reviews");
		NormalizedProductUrl second = normalizer
				.normalize("https://shop.example.com/items/99?color=black&size=M&UTM_CAMPAIGN=spring");

		assertThat(first.canonicalUrl()).isEqualTo("https://shop.example.com/items/99?color=black&size=M");
		assertThat(first.shop()).isNull();
		assertThat(first.supported()).isFalse();
		assertThat(second.canonicalUrl()).isEqualTo(first.canonicalUrl());
	}

	@Test
	void 기본포트와비기본포트가포함된URL이주어지면_정규화를실행하면_기본포트만제거하고비기본포트는유지한다() {
		assertThat(normalizer.normalize("http://example.com:80/items/1").canonicalUrl())
				.isEqualTo("http://example.com/items/1");
		assertThat(normalizer.normalize("https://example.com:443/items/1").canonicalUrl())
				.isEqualTo("https://example.com/items/1");
		assertThat(normalizer.normalize("https://example.com:8443/items/1").canonicalUrl())
				.isEqualTo("https://example.com:8443/items/1");
	}

	@ParameterizedTest
	@ValueSource(strings = {"https://evil-musinsa.com/products/1234567",
			"https://musinsa.com.evil.com/products/1234567", "https://fakea-bly.com/goods/123",
			"https://notzigzag.kr/catalog/products/123"})
	void 유사도메인스푸핑URL이주어지면_정규화를실행하면_미지원쇼핑몰로판정한다(String spoofedUrl) {
		NormalizedProductUrl result = normalizer.normalize(spoofedUrl);

		assertThat(result.supported()).isFalse();
		assertThat(result.shop()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "   ", "javascript:alert(1)", "file:///etc/passwd", "data:text/html,<h1>hi</h1>",
			"ftp://example.com/item", "www.musinsa.com/products/1234567", "https:///products/1234567", "https://",
			"https://exa mple.com/item", "https://user:pass@www.musinsa.com/products/1234567",
			"https://example.com:0/item", "https://example.com:70000/item"})
	void 비허용스킴이나잘못된형식URL이주어지면_정규화를실행하면_잘못된상품URL예외를던진다(String invalidUrl) {
		assertThatThrownBy(() -> normalizer.normalize(invalidUrl)).isInstanceOf(BusinessException.class)
				.extracting(exception -> ((BusinessException) exception).errorCode())
				.isEqualTo(ErrorCode.INVALID_PRODUCT_URL);
	}

	@Test
	void 최대길이를초과하거나널인URL이주어지면_정규화를실행하면_잘못된상품URL예외를던진다() {
		String overLimitUrl = "https://example.com/" + "a".repeat(ProductUrlNormalizer.MAX_URL_LENGTH);

		assertThatThrownBy(() -> normalizer.normalize(null)).isInstanceOf(BusinessException.class)
				.extracting(exception -> ((BusinessException) exception).errorCode())
				.isEqualTo(ErrorCode.INVALID_PRODUCT_URL);
		assertThatThrownBy(() -> normalizer.normalize(overLimitUrl)).isInstanceOf(BusinessException.class)
				.extracting(exception -> ((BusinessException) exception).errorCode())
				.isEqualTo(ErrorCode.INVALID_PRODUCT_URL);
	}

	@Test
	void 쇼핑몰별대표이미지URL이주어지면_이미지호스트허용여부를검사하면_허용된HTTPS호스트만통과한다() {
		assertThat(normalizer.isAllowedImageHost("무신사", "https://image.msscdn.net/images/goods_img/123.jpg")).isTrue();
		assertThat(normalizer.isAllowedImageHost("에이블리", "https://d3ha2047wt6x28.cloudfront.net/goods/123.jpg"))
				.isTrue();
		assertThat(normalizer.isAllowedImageHost("지그재그", "https://cf.product-image.s.zigzag.kr/original/123.jpg"))
				.isTrue();

		assertThat(normalizer.isAllowedImageHost("무신사", "http://image.msscdn.net/images/goods_img/123.jpg")).isFalse();
		assertThat(normalizer.isAllowedImageHost("무신사", "https://image.msscdn.net:8443/images/goods_img/123.jpg"))
				.isFalse();
		assertThat(normalizer.isAllowedImageHost("무신사", "https://d3ha2047wt6x28.cloudfront.net/goods/123.jpg"))
				.isFalse();
		assertThat(normalizer.isAllowedImageHost("무신사", "https://evil-image.msscdn.net/123.jpg")).isFalse();
		assertThat(normalizer.isAllowedImageHost("무신사", "https://image.msscdn.net.evil.com/123.jpg")).isFalse();
		assertThat(normalizer.isAllowedImageHost("미지원몰", "https://image.msscdn.net/123.jpg")).isFalse();
		assertThat(normalizer.isAllowedImageHost(null, "https://image.msscdn.net/123.jpg")).isFalse();

		assertThat(normalizer.isValidHttpsUrl("https://cdn.example.com/item.webp")).isTrue();
		assertThat(normalizer.isValidHttpsUrl("http://cdn.example.com/item.webp")).isFalse();
	}

	@Test
	void 추출규칙레지스트리가초기화되면_ETag를비교하면_강한ETag와약한ETag및목록을올바르게판정한다() {
		String etag = registry.etag();

		assertThat(etag).startsWith("\"" + ExtractionRuleRegistry.CURRENT_VERSION + "-").endsWith("\"");
		assertThat(registry.matchesEtag(etag)).isTrue();
		assertThat(registry.matchesEtag("W/" + etag)).isTrue();
		assertThat(registry.matchesEtag("\"old\", " + etag)).isTrue();
		assertThat(registry.matchesEtag("*")).isTrue();
		assertThat(registry.matchesEtag("\"different\"")).isFalse();
		assertThat(registry.matchesEtag(null)).isFalse();
		assertThat(registry.matchesEtag("  ")).isFalse();
	}
}
