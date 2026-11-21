package com.ject.ziper.domain.product.dto;

import com.ject.ziper.domain.board.entity.ProductBoard;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "상품 카드가 속한 보드 요약 정보")
public record ProductCardBoardSummary(
		@Schema(description = "보드 ID", example = "1", requiredMode = RequiredMode.REQUIRED) Long boardId,
		@Schema(description = "보드 이름", example = "기본 보드", requiredMode = RequiredMode.REQUIRED) String name,
		@Schema(description = "커스텀 보드 여부 (기본 보드는 false, 커스텀 보드는 true)", example = "false", requiredMode = RequiredMode.REQUIRED) boolean modifiable) {

	public static ProductCardBoardSummary from(ProductBoard board) {
		return new ProductCardBoardSummary(board.getId(), board.getName(), board.isModifiable());
	}
}
