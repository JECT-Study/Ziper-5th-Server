package com.ject.ziper.domain.product.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "상품 카드 수정 요청")
public record UpdateProductCardRequest(
		@Schema(description = "상품명 (HTML 태그 제거·공백 정규화 후 1~200자)", example = "오버핏 코튼 티셔츠", minLength = 1, maxLength = 200, requiredMode = RequiredMode.REQUIRED) String productTitle,
		@Schema(description = "쇼핑몰명 (1차 지원 쇼핑몰은 서버 정규화 판정값이 유지되며, 미지원 몰만 최대 100자 선택 입력 허용)", example = "무신사", maxLength = 100, nullable = true) String shop,
		@Schema(description = "상품 가격 (0원 이상 10억원 이하 KRW 정수)", example = "29000", minimum = "0", maximum = "1000000000", requiredMode = RequiredMode.REQUIRED) Long price,
		@Schema(description = "한 줄 메모 (앞뒤 공백 제거 후 최대 100자, 공백만 입력 시 null 저장)", example = "세일할 때 구매하기", maxLength = 100, nullable = true) String memo,
		@Schema(description = "외부 OG 이미지 URL (https 필수, 1차 지원몰은 허용된 imageHosts만 허용)", example = "https://image.msscdn.net/images/goods/1234567.jpg", nullable = true) String imageUrl,
		@Schema(description = "직접 업로드한 썸네일 파일 ID (POST /api/v1/files/images 응답의 fileId)", example = "10", nullable = true) Long imageFileId,
		@Schema(description = "카테고리 대체 아이콘 (TOP | PANTS | SKIRT | ACCESSORY | BEAUTY)", example = "TOP", nullable = true) String fallbackIcon,
		@Schema(description = "연결할 해시태그명 목록 (1개 이상 필수, 선행 # 및 앞뒤 공백 제거 후 1~6자, 미존재 시 커스텀 태그 자동 생성)", example = "[\"봄\", \"데이트\"]", requiredMode = RequiredMode.REQUIRED) List<String> tags,
		@Schema(description = "저장할 활성 보드 ID 목록 (1개 이상 필수, 중복 자동 제거)", example = "[1, 2]", requiredMode = RequiredMode.REQUIRED) List<Long> boardIds) {
}
