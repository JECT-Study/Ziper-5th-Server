package com.ject.ziper.domain.product.extraction;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "상품 대표 이미지 저장 전략 (SERVER_MIRROR: 서버 미러링, CLIENT_UPLOAD: 앱 직접 업로드)")
public enum ImageStrategy {
	SERVER_MIRROR, CLIENT_UPLOAD
}
