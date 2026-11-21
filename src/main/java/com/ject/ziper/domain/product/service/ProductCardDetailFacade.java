package com.ject.ziper.domain.product.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.domain.product.dto.OpenOriginalResponse;
import com.ject.ziper.domain.product.dto.ProductCardDetailResponse;
import com.ject.ziper.domain.product.dto.UpdateProductCardRequest;

/**
 * 상세 조회·수정 트랜잭션을 커밋한 뒤에 응답을 조립한다. 스토리지 서명 호출은 DB 연결을 잡지 않고, 실패해도 이미 커밋된 수정을
 * 되돌리지 않는다. 서명 호출 전체가 실패하면 예외를 그대로 전파해 5xx로 응답하며(수정은 저장된 채 남는다), 일괄 서명 응답에서 해당
 * 파일만 빠진 경우에는 {@code imageFileUrl = null}로 응답한다. 이 클래스는 {@code @Transactional}을
 * 붙이지 않는다.
 */
@Service
public class ProductCardDetailFacade {
	private final ProductCardDetailService detailService;
	private final FileImageService fileImageService;

	public ProductCardDetailFacade(ProductCardDetailService detailService, FileImageService fileImageService) {
		this.detailService = detailService;
		this.fileImageService = fileImageService;
	}

	public ProductCardDetailResponse getProductCardDetail(Long userId, Long cardId) {
		return toResponse(detailService.getProductCardDetail(userId, cardId));
	}

	public ProductCardDetailResponse updateProductCard(Long userId, Long cardId, UpdateProductCardRequest request) {
		return toResponse(detailService.updateProductCard(userId, cardId, request));
	}

	public OpenOriginalResponse openOriginalUrl(Long userId, Long cardId) {
		return detailService.openOriginalUrl(userId, cardId);
	}

	public void deleteProductCard(Long userId, Long cardId) {
		detailService.deleteProductCard(userId, cardId);
	}

	private ProductCardDetailResponse toResponse(ProductCardDetailView view) {
		Long fileId = view.card().getImageFileId();
		String imageFileUrl = fileId == null ? null : fileImageService.findSignedUrls(List.of(fileId)).get(fileId);
		return ProductCardDetailResponse.of(view.card(), imageFileUrl, view.tags(), view.boards());
	}
}
