package com.ject.ziper.domain.home.dto;

import java.time.Instant;

import com.ject.ziper.domain.product.entity.FallbackIcon;
import com.ject.ziper.domain.product.entity.ProductImageStatus;
import com.ject.ziper.domain.product.link.dto.ProductLinkStatusResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "홈 화면 최근 저장 상품 응답")
public record HomeRecentProductResponse(
		@Schema(description = "상품 카드 ID", example = "1", requiredMode = RequiredMode.REQUIRED) Long cardId,
		@Schema(description = "쇼핑몰명 (미지원 몰 또는 미입력 시 null)", example = "무신사", nullable = true) String shop,
		@Schema(description = "상품명", example = "오버핏 코튼 티셔츠", requiredMode = RequiredMode.REQUIRED) String productTitle,
		@Schema(description = "상품 가격 (KRW 원 단위)", example = "29000", requiredMode = RequiredMode.REQUIRED) Long price,
		@Schema(description = "원본 외부 이미지 URL (없으면 null)", example = "https://image.msscdn.net/images/goods/1234567.jpg", nullable = true) String imageUrl,
		@Schema(description = "자체 저장소 썸네일 Signed URL (파일이 없으면 null)", example = "https://storage.example/signed/thumbnails/2026/11/abc.webp", nullable = true) String imageFileUrl,
		@Schema(description = "카테고리 대체 아이콘 (없으면 null)", example = "TOP", nullable = true) FallbackIcon fallbackIcon,
		@Schema(description = "이미지 미러링/준비 상태 (NONE | PENDING | READY | FAILED)", example = "READY", requiredMode = RequiredMode.REQUIRED) ProductImageStatus imageStatus,
		@Schema(description = "공유 상품 링크 상태 정보", requiredMode = RequiredMode.REQUIRED) ProductLinkStatusResponse link,
		@Schema(description = "상품 카드 생성 시각 (UTC ISO-8601)", example = "2026-11-21T12:00:00Z", requiredMode = RequiredMode.REQUIRED) Instant createdAt) {
}
