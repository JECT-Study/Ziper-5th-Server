package com.ject.ziper.domain.trash.service;

import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.domain.trash.dto.TrashProductItemResponse;
import com.ject.ziper.domain.trash.dto.TrashProductListResponse;
import com.ject.ziper.domain.trash.repository.TrashQueryRepository.TrashProductRow;

@Service
public class TrashQueryFacade {
	private final TrashQueryService queryService;
	private final FileImageService fileImageService;

	public TrashQueryFacade(TrashQueryService queryService, FileImageService fileImageService) {
		this.queryService = queryService;
		this.fileImageService = fileImageService;
	}

	public TrashProductListResponse getProducts(Long userId) {
		var rows = queryService.loadProducts(userId);
		Map<Long, String> signedUrls = fileImageService.findSignedUrls(
				rows.stream().map(TrashProductRow::imageFileId).filter(Objects::nonNull).distinct().toList());
		return new TrashProductListResponse(rows.stream().map(row -> {
			String signedUrl = row.imageFileId() == null ? null : signedUrls.get(row.imageFileId());
			String imageUrl = signedUrl != null ? signedUrl : row.imageUrl();
			return new TrashProductItemResponse(row.cardId(), row.shop(), row.productTitle(), row.price(), imageUrl,
					row.daysRemaining());
		}).toList());
	}
}
