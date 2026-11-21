package com.ject.ziper.domain.board.dto;

import java.time.Instant;
import java.util.List;

import com.ject.ziper.domain.product.entity.FallbackIcon;
import com.ject.ziper.domain.product.entity.ProductImageStatus;
import com.ject.ziper.domain.product.link.dto.ProductLinkStatusResponse;
import com.ject.ziper.domain.tag.dto.TagResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "보드 상세 상품 카드 항목 응답")
public record BoardProductItemResponse(
		@Schema(description = "상품 카드 ID", example = "1", requiredMode = RequiredMode.REQUIRED) Long cardId,
		@Schema(description = "쇼핑몰명 (없으면 null)", example = "무신사", nullable = true) String shop,
		@Schema(description = "전체 상품명 (말줄임 처리 없이 원본 전체 반환)", example = "오버핏 코튼 티셔츠", requiredMode = RequiredMode.REQUIRED) String productTitle,
		@Schema(description = "상품 가격 (KRW 원 단위)", example = "29000", requiredMode = RequiredMode.REQUIRED) Long price,
		@Schema(description = "한 줄 메모 (없으면 null)", example = "세일할 때 구매하기", nullable = true) String memo,
		@Schema(description = "원본 외부 이미지 URL (없으면 null)", example = "https://image.msscdn.net/images/goods/1234567.jpg", nullable = true) String imageUrl,
		@Schema(description = "자체 저장소 썸네일 Signed URL (imageFileId가 없거나 조회 실패 시 null)", example = "https://storage.example/signed/thumbnails/2026/11/abc.webp", nullable = true) String imageFileUrl,
		@Schema(description = "카테고리 대체 아이콘 (없으면 null)", example = "TOP", nullable = true) FallbackIcon fallbackIcon,
		@Schema(description = "이미지 미러링/준비 상태 (NONE | PENDING | READY | FAILED)", example = "READY", requiredMode = RequiredMode.REQUIRED) ProductImageStatus imageStatus,
		@Schema(description = "공유 상품 링크 상태 정보", requiredMode = RequiredMode.REQUIRED) ProductLinkStatusResponse link,
		@Schema(description = "연결된 해시태그 목록 (기본 태그 우선, ID 오름차순)", requiredMode = RequiredMode.REQUIRED) List<TagResponse> tags,
		@Schema(description = "해당 보드에 상품이 추가된 시각 (UTC ISO-8601)", example = "2026-11-21T14:00:00Z", requiredMode = RequiredMode.REQUIRED) Instant addedAt) {
}
