package com.ject.ziper.domain.product.extraction;

import java.util.List;

public record GenericExtractionRule(List<String> title, List<String> image) {
	public GenericExtractionRule {
		title = List.copyOf(title);
		image = List.copyOf(image);
	}
}
