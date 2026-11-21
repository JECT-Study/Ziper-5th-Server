package com.ject.ziper.domain.file.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ImageUploadResponse(@Schema(description = "저장된 파일 ID", example = "1") Long fileId,
		@Schema(description = "비공개 버킷 단기 서명 URL", example = "https://project.supabase.co/storage/v1/object/sign/product-images/thumbnails/2026/11/uuid.webp?token=...") String url,
		@Schema(description = "저장된 MIME 타입", example = "image/webp") String contentType,
		@Schema(description = "저장된 WebP 썸네일 크기(바이트)", example = "28410") long fileSize) {
}
