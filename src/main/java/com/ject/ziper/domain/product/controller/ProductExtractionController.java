package com.ject.ziper.domain.product.controller;

import java.util.regex.Pattern;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.product.dto.ExtractionRulesResponse;
import com.ject.ziper.domain.product.dto.ProductUrlCheckRequest;
import com.ject.ziper.domain.product.dto.ProductUrlCheckResponse;
import com.ject.ziper.domain.product.service.ProductExtractionService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "상품 URL 및 추출 규칙", description = "앱 내 상품 정보 추출 규칙 설정 조회 및 외부 요청 없는 상품 URL 정규화·중복 확인 API")
public class ProductExtractionController {
	private static final String RULES_CACHE_CONTROL = "private, max-age=300, must-revalidate";
	private static final Pattern POSITIVE_LONG_PATTERN = Pattern.compile("^[1-9]\\d{0,17}$");

	private final ProductExtractionService extractionService;

	public ProductExtractionController(ProductExtractionService extractionService) {
		this.extractionService = extractionService;
	}

	@GetMapping("/extraction-rules")
	@Operation(summary = "상품 정보 추출 규칙 설정 조회", description = "1차 지원 쇼핑몰별 정규화 패턴, 메타데이터 추출 선택자 및 이미지 미러링 전략을 조회합니다. ETag / If-None-Match 조건부 요청(304 Not Modified)을 지원합니다.")
	public ResponseEntity<ExtractionRulesResponse> getExtractionRules(@AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "이전에 수신한 ETag 값 (일치 시 304 Not Modified 반환)", example = "\"2026-11-21.1-000000000000\"") @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
		String etag = extractionService.getRulesEtag();
		if (extractionService.matchesRulesEtag(ifNoneMatch)) {
			return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag)
					.header(HttpHeaders.CACHE_CONTROL, RULES_CACHE_CONTROL).build();
		}
		return ResponseEntity.ok().eTag(etag).header(HttpHeaders.CACHE_CONTROL, RULES_CACHE_CONTROL)
				.body(extractionService.getExtractionRules());
	}

	@PostMapping("/url-check")
	@Operation(summary = "상품 URL 확인 및 정규화", description = "외부 HTTP 요청 없이 URL 형식과 스킴을 검증하고 1차 지원 쇼핑몰 판정, 표준 URL 정규화 및 기존 저장 카드 중복 여부를 반환합니다.")
	public ResponseEntity<ProductUrlCheckResponse> checkUrl(@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody ProductUrlCheckRequest request) {
		long userId = parseUserId(jwt);
		return ResponseEntity.ok(extractionService.checkUrl(userId, request.url()));
	}

	private static long parseUserId(Jwt jwt) {
		if (jwt == null || jwt.getSubject() == null || !POSITIVE_LONG_PATTERN.matcher(jwt.getSubject()).matches()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return Long.parseLong(jwt.getSubject());
	}
}
