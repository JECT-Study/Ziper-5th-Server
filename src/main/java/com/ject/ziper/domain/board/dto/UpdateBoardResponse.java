package com.ject.ziper.domain.board.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "보드 이름 수정 응답")
public record UpdateBoardResponse(@Schema(description = "수정된 보드 ID", example = "2") Long boardId,
		@Schema(description = "변경된 보드 이름", example = "여름 원피스") String name) {
}
