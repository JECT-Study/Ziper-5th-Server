package com.ject.ziper.domain.home.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.home.dto.HomeSummaryResponse;
import com.ject.ziper.domain.home.service.HomeQueryService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/home")
@Tag(name = "홈", description = "홈 화면 요약 조회 API")
public class HomeController {
	private static final String MAX_LONG_TEXT = String.valueOf(Long.MAX_VALUE);

	private final HomeQueryService homeQueryService;

	public HomeController(HomeQueryService homeQueryService) {
		this.homeQueryService = homeQueryService;
	}

	@GetMapping
	@Operation(summary = "홈 화면 요약 조회", description = "재발견 대기 상품 개수, 순수 최신 생성순 내 보드 미리보기(보드별 활성 상품 개수 및 대표 썸네일 최대 3장), 최근 저장한 구매 예정(PLANNED) 상품 목록을 조회합니다.")
	public ResponseEntity<HomeSummaryResponse> getHomeSummary(
			@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "최근 저장 상품 최대 조회 개수 (1~50, 기본값 10)", example = "10") @RequestParam(defaultValue = "10") int recentLimit) {
		HomeSummaryResponse response = homeQueryService.getHomeSummary(extractUserId(jwt), recentLimit);
		return ResponseEntity.ok(response);
	}

	private static Long extractUserId(Jwt jwt) {
		if (jwt == null) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		String subject = jwt.getSubject();
		if (subject == null || subject.isEmpty() || subject.length() > MAX_LONG_TEXT.length()
				|| !subject.chars().allMatch(Character::isDigit)) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		if (subject.length() == MAX_LONG_TEXT.length() && subject.compareTo(MAX_LONG_TEXT) > 0) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		long userId = Long.parseLong(subject);
		if (userId <= 0L) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return userId;
	}
}
