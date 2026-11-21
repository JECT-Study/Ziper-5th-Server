package com.ject.ziper.domain.board.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "구매 완료 목록 요약 응답")
public record PurchasedSummaryResponse(
		@Schema(description = "활성 구매 완료(PURCHASED) 상품 수", example = "3", requiredMode = RequiredMode.REQUIRED) int productCount,
		@Schema(description = "최근 구매 완료된 상품 대표 썸네일 목록 (최대 3장)", requiredMode = RequiredMode.REQUIRED) List<BoardPreviewImageResponse> previewImages) {
}
