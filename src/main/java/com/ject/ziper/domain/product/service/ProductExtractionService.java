package com.ject.ziper.domain.product.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.product.dto.ExtractionRulesResponse;
import com.ject.ziper.domain.product.dto.ProductUrlCheckResponse;
import com.ject.ziper.domain.product.extraction.ExtractionRuleRegistry;
import com.ject.ziper.domain.product.extraction.NormalizedProductUrl;
import com.ject.ziper.domain.product.extraction.ProductUrlNormalizer;
import com.ject.ziper.domain.product.repository.ProductUrlLookupRepository;

@Service
public class ProductExtractionService {
	private final ExtractionRuleRegistry ruleRegistry;
	private final ProductUrlNormalizer urlNormalizer;
	private final ProductUrlLookupRepository urlLookupRepository;

	public ProductExtractionService(ExtractionRuleRegistry ruleRegistry, ProductUrlNormalizer urlNormalizer,
			ProductUrlLookupRepository urlLookupRepository) {
		this.ruleRegistry = ruleRegistry;
		this.urlNormalizer = urlNormalizer;
		this.urlLookupRepository = urlLookupRepository;
	}

	public String getRulesEtag() {
		return ruleRegistry.etag();
	}

	public boolean matchesRulesEtag(String ifNoneMatch) {
		return ruleRegistry.matchesEtag(ifNoneMatch);
	}

	public ExtractionRulesResponse getExtractionRules() {
		return ExtractionRulesResponse.from(ruleRegistry.version(), ruleRegistry.shops(), ruleRegistry.generic());
	}

	@Transactional(readOnly = true)
	public ProductUrlCheckResponse checkUrl(long userId, String rawUrl) {
		NormalizedProductUrl normalized = urlNormalizer.normalize(rawUrl);
		Long existingCardId = urlLookupRepository.findCardIdByUserIdAndProductUrl(userId, normalized.canonicalUrl())
				.orElse(null);
		return new ProductUrlCheckResponse(normalized.canonicalUrl(), normalized.shop(), normalized.supported(),
				existingCardId);
	}
}
