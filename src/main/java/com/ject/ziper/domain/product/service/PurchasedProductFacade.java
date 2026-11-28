package com.ject.ziper.domain.product.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.domain.product.dto.PurchasedProductItemResponse;
import com.ject.ziper.domain.product.dto.PurchasedProductListResponse;
import com.ject.ziper.domain.product.repository.PurchasedProductRepository.PurchasedRow;
import com.ject.ziper.domain.product.service.PurchasedProductService.PurchasedPage;

@Service
public class PurchasedProductFacade {
	private final PurchasedProductService service;
	private final FileImageService images;

	public PurchasedProductFacade(PurchasedProductService service, FileImageService images) {
		this.service = service;
		this.images = images;
	}

	public PurchasedProductListResponse getPurchased(Long userId, String sort, String cursor, Integer size) {
		PurchasedPage page = service.loadPurchased(userId, sort, cursor, size);
		Map<Long, String> signed = images.findSignedUrls(
				page.rows().stream().map(PurchasedRow::imageFileId).filter(Objects::nonNull).distinct().toList());
		List<PurchasedProductItemResponse> items = page.rows().stream().map(row -> {
			String signedUrl = row.imageFileId() == null ? null : signed.get(row.imageFileId());
			String imageUrl = signedUrl != null ? signedUrl : row.imageUrl();
			return new PurchasedProductItemResponse(row.cardId(), row.productUrl(), row.shop(), row.productTitle(),
					row.price(), imageUrl, row.fallbackIcon(), row.tag(), row.createdAt());
		}).toList();
		return new PurchasedProductListResponse(page.totalCount(), items, page.nextCursor(), page.nextCursor() != null);
	}
}
