package com.ject.ziper.domain.product.dto;

import java.util.List;

import com.ject.ziper.domain.product.extraction.ExtractionFieldRules;
import com.ject.ziper.domain.product.extraction.GenericExtractionRule;
import com.ject.ziper.domain.product.extraction.ImageStrategy;
import com.ject.ziper.domain.product.extraction.ShopExtractionRule;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "상품 정보 추출 규칙 설정 응답")
public record ExtractionRulesResponse(
		@Schema(description = "추출 규칙 버전", example = "2026-11-21.1", requiredMode = RequiredMode.REQUIRED) String version,
		@Schema(description = "1차 지원 쇼핑몰별 추출 및 URL 정규화 규칙 목록", requiredMode = RequiredMode.REQUIRED) List<ShopRule> shops,
		@Schema(description = "미지원 쇼핑몰 공통 폴백 추출 규칙", requiredMode = RequiredMode.REQUIRED) GenericRule generic) {

	public static ExtractionRulesResponse from(String version, List<ShopExtractionRule> shopRules,
			GenericExtractionRule genericRule) {
		List<ShopRule> mappedShops = shopRules.stream().map(ShopRule::from).toList();
		GenericRule mappedGeneric = new GenericRule(new GenericFields(genericRule.title(), genericRule.image()));
		return new ExtractionRulesResponse(version, mappedShops, mappedGeneric);
	}

	@Schema(description = "개별 지원 쇼핑몰 추출 규칙")
	public record ShopRule(
			@Schema(description = "쇼핑몰 표시명", example = "무신사", requiredMode = RequiredMode.REQUIRED) String shop,
			@Schema(description = "지원 쇼핑몰 호스트 목록", example = "[\"musinsa.com\", \"www.musinsa.com\", \"m.musinsa.com\", \"store.musinsa.com\"]", requiredMode = RequiredMode.REQUIRED) List<String> hosts,
			@Schema(description = "표준 상품 URL 정규화 규칙", requiredMode = RequiredMode.REQUIRED) CanonicalRule canonical,
			@Schema(description = "상품 메타데이터 추출 선택자 규칙", requiredMode = RequiredMode.REQUIRED) ShopFields fields,
			@Schema(description = "허용된 상품 대표 이미지 CDN 호스트 목록", example = "[\"image.msscdn.net\"]", requiredMode = RequiredMode.REQUIRED) List<String> imageHosts,
			@Schema(description = "대표 이미지 저장 전략", example = "SERVER_MIRROR", requiredMode = RequiredMode.REQUIRED) ImageStrategy imageStrategy) {

		private static ShopRule from(ShopExtractionRule rule) {
			ExtractionFieldRules fieldRules = rule.fields();
			return new ShopRule(rule.shop(), rule.hosts(),
					new CanonicalRule(rule.canonicalPattern(), rule.canonicalTemplate()),
					new ShopFields(fieldRules.title(), fieldRules.price(), fieldRules.image(),
							fieldRules.availability()),
					rule.imageHosts(), rule.imageStrategy());
		}
	}

	@Schema(description = "표준 URL 정규화 패턴 및 템플릿")
	public record CanonicalRule(
			@Schema(description = "상품 경로 정규식 패턴", example = "/(?:products|app/goods)/(\\d+)", requiredMode = RequiredMode.REQUIRED) String pattern,
			@Schema(description = "표준 URL 생성 템플릿", example = "https://www.musinsa.com/products/{1}", requiredMode = RequiredMode.REQUIRED) String template) {
	}

	@Schema(description = "쇼핑몰 메타데이터 필드별 추출 선택자 목록")
	public record ShopFields(
			@Schema(description = "상품명 추출 선택자 우선순위 목록", requiredMode = RequiredMode.REQUIRED) List<String> title,
			@Schema(description = "가격 추출 선택자 우선순위 목록", requiredMode = RequiredMode.REQUIRED) List<String> price,
			@Schema(description = "대표 이미지 추출 선택자 우선순위 목록", requiredMode = RequiredMode.REQUIRED) List<String> image,
			@Schema(description = "판매·품절 상태 추출 선택자 우선순위 목록", requiredMode = RequiredMode.REQUIRED) List<String> availability) {
	}

	@Schema(description = "미지원 쇼핑몰 공통 추출 규칙")
	public record GenericRule(
			@Schema(description = "공통 메타데이터 추출 선택자 규칙", requiredMode = RequiredMode.REQUIRED) GenericFields fields) {
	}

	@Schema(description = "공통 메타데이터 필드별 추출 선택자 목록")
	public record GenericFields(
			@Schema(description = "상품명 추출 선택자 우선순위 목록", requiredMode = RequiredMode.REQUIRED) List<String> title,
			@Schema(description = "대표 이미지 추출 선택자 우선순위 목록", requiredMode = RequiredMode.REQUIRED) List<String> image) {
	}
}
