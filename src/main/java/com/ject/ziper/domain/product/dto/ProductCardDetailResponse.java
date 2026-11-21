package com.ject.ziper.domain.product.dto;

import java.time.Instant;
import java.util.List;

import com.ject.ziper.domain.product.entity.FallbackIcon;
import com.ject.ziper.domain.product.entity.MetadataSource;
import com.ject.ziper.domain.product.entity.ProductCard;
import com.ject.ziper.domain.product.entity.ProductCardStatus;
import com.ject.ziper.domain.product.entity.ProductImageStatus;
import com.ject.ziper.domain.product.link.dto.ProductLinkStatusResponse;
import com.ject.ziper.domain.tag.dto.TagResponse;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "상품 카드 상세 조회·수정 응답")
public record ProductCardDetailResponse(
		@Schema(description = "상품 카드 ID", example = "1", requiredMode = RequiredMode.REQUIRED) Long cardId,
		@Schema(description = "서버 정규화가 완료된 표준 상품 URL", example = "https://www.musinsa.com/products/1234567", requiredMode = RequiredMode.REQUIRED) String productUrl,
		@Schema(description = "원본 공유 URL (미전달 시 null)", example = "https://musinsa.onelink.me/abc123", nullable = true) String sharedUrl,
		@Schema(description = "쇼핑몰명 (1차 지원몰은 서버 판정값, 미지원 몰은 입력값 또는 null)", example = "무신사", nullable = true) String shop,
		@Schema(description = "상품명 전체 텍스트 (잘림 없음)", example = "오버핏 코튼 티셔츠", requiredMode = RequiredMode.REQUIRED) String productTitle,
		@Schema(description = "상품 가격 (KRW 원 단위)", example = "29000", requiredMode = RequiredMode.REQUIRED) Long price,
		@Schema(description = "한 줄 메모 (없으면 null)", example = "세일할 때 구매하기", nullable = true) String memo,
		@Schema(description = "원본 외부 이미지 URL (없으면 null)", example = "https://image.msscdn.net/images/goods/1234567.jpg", nullable = true) String imageUrl,
		@Schema(description = "자체 저장소 썸네일 파일 ID (없으면 null)", example = "10", nullable = true) Long imageFileId,
		@Schema(description = "자체 저장소 썸네일 Signed URL (imageFileId가 없으면 null)", example = "https://storage.example/signed/thumbnails/2026/11/abc.webp", nullable = true) String imageFileUrl,
		@Schema(description = "카테고리 대체 아이콘 (없으면 null)", example = "TOP", nullable = true) FallbackIcon fallbackIcon,
		@Schema(description = "이미지 미러링/준비 상태 (NONE | PENDING | READY | FAILED)", example = "READY", requiredMode = RequiredMode.REQUIRED) ProductImageStatus imageStatus,
		@Schema(description = "메타데이터 출처 (CLIENT | MANUAL)", example = "CLIENT", requiredMode = RequiredMode.REQUIRED) MetadataSource metadataSource,
		@Schema(description = "상품 카드 상태 (PLANNED | PURCHASED)", example = "PLANNED", requiredMode = RequiredMode.REQUIRED) ProductCardStatus status,
		@Schema(description = "공유 상품 링크 상태 정보", requiredMode = RequiredMode.REQUIRED) ProductLinkStatusResponse link,
		@Schema(description = "현재 재발견 단계 (0: 14일, 1: 30일, 2: 90일)", example = "0", requiredMode = RequiredMode.REQUIRED) int rediscoveryStage,
		@Schema(description = "마지막 상호작용 시각 (UTC ISO-8601)", example = "2026-11-21T12:00:00Z", requiredMode = RequiredMode.REQUIRED) Instant lastInteractedAt,
		@Schema(description = "다음 재발견 예정 시각 (UTC ISO-8601)", example = "2026-12-05T12:00:00Z", requiredMode = RequiredMode.REQUIRED) Instant nextRediscoveryAt,
		@Schema(description = "연결된 해시태그 목록 (기본 태그 우선, ID 오름차순)", requiredMode = RequiredMode.REQUIRED) List<TagResponse> tags,
		@Schema(description = "연결된 활성 보드 목록 (생성 시각 내림차순, ID 내림차순)", requiredMode = RequiredMode.REQUIRED) List<ProductCardBoardSummary> boards,
		@Schema(description = "상품 카드 생성 시각 (UTC ISO-8601)", example = "2026-11-21T12:00:00Z", requiredMode = RequiredMode.REQUIRED) Instant createdAt,
		@Schema(description = "상품 카드 마지막 수정 시각 (UTC ISO-8601)", example = "2026-11-21T12:30:00Z", requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {

	public static ProductCardDetailResponse of(ProductCard card, String imageFileUrl, List<TagResponse> tags,
			List<ProductCardBoardSummary> boards) {
		ProductLinkStatusResponse link = new ProductLinkStatusResponse(card.getLinkStatus(), card.getLinkCheckedAt(),
				card.getLinkCheckSource());
		return new ProductCardDetailResponse(card.getId(), card.getProductUrl(), card.getSharedUrl(), card.getShop(),
				card.getProductTitle(), card.getPrice(), card.getMemo(), card.getImageUrl(), card.getImageFileId(),
				imageFileUrl, card.getFallbackIcon(), card.getImageStatus(), card.getMetadataSource(), card.getStatus(),
				link, card.getRediscoveryStage(), card.getLastInteractedAt(), card.getNextRediscoveryAt(),
				List.copyOf(tags), List.copyOf(boards), card.getCreatedAt(), card.getUpdatedAt());
	}
}
