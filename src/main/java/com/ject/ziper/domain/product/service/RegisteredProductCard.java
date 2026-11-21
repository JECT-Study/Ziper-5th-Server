package com.ject.ziper.domain.product.service;

import java.util.List;

import com.ject.ziper.domain.product.entity.ProductCard;
import com.ject.ziper.domain.tag.entity.Tag;

/** 등록 트랜잭션이 커밋한 결과. 서명 URL 같은 외부 호출이 필요한 응답 조립은 트랜잭션 밖에서 한다. */
public record RegisteredProductCard(ProductCard card, List<Long> boardIds, List<Tag> tags) {
}
