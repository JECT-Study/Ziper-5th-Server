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

@Schema(description = "상품 카드 신규 등록 응답")
public record CreateProductCardResponse(
		@Schema(description = "생성된 상품 카드 ID", example = "1", requiredMode = RequiredMode.REQUIRED) Long cardId,
		@Schema(description = "서버 정규화가 완료된 표준 상품 URL", example = "https://www.musinsa.com/products/1234567", requiredMode = RequiredMode.REQUIRED) String productUrl,
		@Schema(description = "원본 공유 URL (미전달 시 null)", example = "https://musinsa.onelink.me/abc123", nullable = true) String sharedUrl,
		@Schema(description = "쇼핑몰명 (1차 지원몰은 서버 판정값, 미지원 몰은 입력값 또는 null)", example = "무신사", nullable = true) String shop,
		@Schema(description = "HTML 태그 제거 및 공백 정규화가 완료된 상품명", example = "오버핏 코튼 티셔츠", requiredMode = RequiredMode.REQUIRED) String productTitle,
		@Schema(description = "상품 가격 (KRW 원 단위)", example = "29000", requiredMode = RequiredMode.REQUIRED) Long price,
		@Schema(description = "한 줄 메모 (없으면 null)", example = "세일할 때 구매하기", nullable = true) String memo,
		@Schema(description = "원본 외부 이미지 URL (없으면 null)", example = "https://image.msscdn.net/images/goods/1234567.jpg", nullable = true) String imageUrl,
		@Schema(description = "자체 저장소 썸네일 파일 ID (직접 업로드 시 즉시 설정, 미러링 대기 시 null)", example = "10", nullable = true) Long imageFileId,
		@Schema(description = "자체 저장소 썸네일 Signed URL (imageFileId가 없으면 null)", example = "https://storage.example/signed/thumbnails/2026/11/abc.webp", nullable = true) String imageFileUrl,
		@Schema(description = "카테고리 대체 아이콘 (없으면 null)", example = "TOP", nullable = true) FallbackIcon fallbackIcon,
		@Schema(description = "이미지 미러링/준비 상태 (NONE | PENDING | READY | FAILED)", example = "PENDING", requiredMode = RequiredMode.REQUIRED) ProductImageStatus imageStatus,
		@Schema(description = "메타데이터 출처 (CLIENT | MANUAL)", example = "CLIENT", requiredMode = RequiredMode.REQUIRED) MetadataSource metadataSource,
		@Schema(description = "상품 카드 상태 (PLANNED | PURCHASED)", example = "PLANNED", requiredMode = RequiredMode.REQUIRED) ProductCardStatus status,
		@Schema(description = "공유 상품 링크 상태 정보", requiredMode = RequiredMode.REQUIRED) ProductLinkStatusResponse link,
		@Schema(description = "연결된 보드 ID 목록", example = "[1, 2]", requiredMode = RequiredMode.REQUIRED) List<Long> boardIds,
		@Schema(description = "연결된 해시태그 목록", requiredMode = RequiredMode.REQUIRED) List<TagResponse> tags,
		@Schema(description = "상품 카드 생성 시각 (UTC ISO-8601)", example = "2026-11-21T12:00:00Z", requiredMode = RequiredMode.REQUIRED) Instant createdAt) {

	public static CreateProductCardResponse of(ProductCard card, String imageFileUrl, List<Long> boardIds,
			List<TagResponse> tags) {
		ProductLinkStatusResponse link = new ProductLinkStatusResponse(card.getLinkStatus(), card.getLinkCheckedAt(),
				card.getLinkCheckSource());
		return new CreateProductCardResponse(card.getId(), card.getProductUrl(), card.getSharedUrl(), card.getShop(),
				card.getProductTitle(), card.getPrice(), card.getMemo(), card.getImageUrl(), card.getImageFileId(),
				imageFileUrl, card.getFallbackIcon(), card.getImageStatus(), card.getMetadataSource(), card.getStatus(),
				link, List.copyOf(boardIds), List.copyOf(tags), card.getCreatedAt());
	}
}
