package com.ject.ziper.domain.board.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "보드 목록 항목 요약 응답")
public record BoardSummaryResponse(
		@Schema(description = "보드 ID", example = "1", requiredMode = RequiredMode.REQUIRED) Long boardId,
		@Schema(description = "보드 이름", example = "기본 보드", requiredMode = RequiredMode.REQUIRED) String name,
		@Schema(description = "수정·삭제 가능 여부 (기본 보드는 false, 커스텀 보드는 true)", example = "false", requiredMode = RequiredMode.REQUIRED) boolean modifiable,
		@Schema(description = "보드 내 활성 구매 예정(PLANNED) 상품 수", example = "5", requiredMode = RequiredMode.REQUIRED) int productCount,
		@Schema(description = "최근 추가된 활성 구매 예정 상품 대표 썸네일 목록 (최대 3장)", requiredMode = RequiredMode.REQUIRED) List<BoardPreviewImageResponse> previewImages,
		@Schema(description = "보드 생성 시각 (UTC ISO-8601)", example = "2026-11-21T14:00:00Z", requiredMode = RequiredMode.REQUIRED) Instant createdAt) {
}
