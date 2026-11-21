package com.ject.ziper.domain.product.extraction;

import java.util.List;

public record ExtractionFieldRules(List<String> title, List<String> price, List<String> image,
		List<String> availability) {
	public ExtractionFieldRules {
		title = List.copyOf(title);
		price = List.copyOf(price);
		image = List.copyOf(image);
		availability = List.copyOf(availability);
	}
}
