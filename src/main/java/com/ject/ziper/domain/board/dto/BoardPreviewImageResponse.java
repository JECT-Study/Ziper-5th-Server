package com.ject.ziper.domain.board.dto;

import com.ject.ziper.domain.product.entity.FallbackIcon;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "보드 대표 썸네일 이미지 응답")
public record BoardPreviewImageResponse(
		@Schema(description = "상품 카드 ID", example = "1", requiredMode = RequiredMode.REQUIRED) Long cardId,
		@Schema(description = "대표 썸네일 URL (자체 저장소 Signed URL 우선, 없으면 원본 imageUrl, 모두 없으면 null)", example = "https://storage.example/signed/thumbnails/2026/11/abc.webp", nullable = true) String imageUrl,
		@Schema(description = "카테고리 대체 아이콘 (없으면 null)", example = "TOP", nullable = true) FallbackIcon fallbackIcon) {
}
