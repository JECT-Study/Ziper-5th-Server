package com.ject.ziper.domain.home.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "홈 화면 요약 조회 응답")
public record HomeSummaryResponse(
		@Schema(description = "재발견 대기 상품 개수", example = "3", requiredMode = RequiredMode.REQUIRED) long rediscoveryCount,
		@Schema(description = "내 보드 미리보기 목록 (순수 최신 생성순)", requiredMode = RequiredMode.REQUIRED) List<HomeBoardSummaryResponse> boards,
		@Schema(description = "최근 저장한 구매 예정(PLANNED) 상품 목록", requiredMode = RequiredMode.REQUIRED) List<HomeRecentProductResponse> recentProducts) {
}
