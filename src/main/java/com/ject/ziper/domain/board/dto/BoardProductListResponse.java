package com.ject.ziper.domain.board.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "보드 상세 상품 목록 조회 응답")
public record BoardProductListResponse(
		@Schema(description = "보드 ID", example = "1", requiredMode = RequiredMode.REQUIRED) Long boardId,
		@Schema(description = "보드 이름", example = "기본 보드", requiredMode = RequiredMode.REQUIRED) String name,
		@Schema(description = "수정·삭제 가능 여부 (기본 보드는 false, 커스텀 보드는 true)", example = "false", requiredMode = RequiredMode.REQUIRED) boolean modifiable,
		@Schema(description = "페이지 크기와 관계없이 해당 보드와 태그 필터에 일치하는 활성 구매 예정 상품 수", example = "12", requiredMode = RequiredMode.REQUIRED) int productCount,
		@Schema(description = "현재 페이지 상품 카드 목록", requiredMode = RequiredMode.REQUIRED) List<BoardProductItemResponse> items,
		@Schema(description = "다음 페이지 조회용 커서 (마지막 페이지이면 null)", nullable = true) String nextCursor,
		@Schema(description = "다음 페이지 존재 여부", example = "true", requiredMode = RequiredMode.REQUIRED) boolean hasNext) {
}
