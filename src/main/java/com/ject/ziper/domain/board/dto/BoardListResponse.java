package com.ject.ziper.domain.board.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "보드 목록 조회 응답")
public record BoardListResponse(
		@Schema(description = "활성 보드 목록 (순수 최신 생성순: createdAt DESC, id DESC)", requiredMode = RequiredMode.REQUIRED) List<BoardSummaryResponse> boards,
		@Schema(description = "구매 완료 목록 요약 정보", requiredMode = RequiredMode.REQUIRED) PurchasedSummaryResponse purchasedSummary) {
}
