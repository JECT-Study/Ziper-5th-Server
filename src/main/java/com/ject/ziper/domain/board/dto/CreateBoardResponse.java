package com.ject.ziper.domain.board.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "보드 생성 응답")
public record CreateBoardResponse(@Schema(description = "생성된 보드 ID", example = "2") Long boardId,
		@Schema(description = "보드 이름", example = "봄 아우터") String name,
		@Schema(description = "수정·삭제 가능 여부 (커스텀 보드는 true)", example = "true") boolean modifiable,
		@Schema(description = "보드 내 상품 수 (생성 직후 0)", example = "0") int productCount,
		@Schema(description = "보드 생성 시각 (UTC ISO-8601)", example = "2026-11-21T14:00:00Z") Instant createdAt) {
}
