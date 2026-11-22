package com.ject.ziper.domain.product.extraction;

public record NormalizedProductUrl(String canonicalUrl, String shop, boolean supported) {
}
