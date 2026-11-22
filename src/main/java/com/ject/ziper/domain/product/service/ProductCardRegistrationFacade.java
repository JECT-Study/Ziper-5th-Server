package com.ject.ziper.domain.product.service;

import org.springframework.stereotype.Service;

import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.domain.product.dto.CreateProductCardRequest;
import com.ject.ziper.domain.product.dto.CreateProductCardResponse;
import com.ject.ziper.domain.tag.dto.TagResponse;

/**
 * 등록 트랜잭션을 커밋한 뒤에 응답을 조립한다. 스토리지 서명 호출은 DB 연결을 잡지 않고 실패해도 이미 커밋된 카드를 되돌리지 않는다.
 * 서명 호출 전체가 실패하면 예외를 그대로 전파해 5xx로 응답하며(카드는 저장된 채 남는다), 일괄 서명 응답에서 해당 파일만 빠진
 * 경우에는 {@code imageFileUrl = null}로 응답한다. 이 클래스는 {@code @Transactional}을 붙이지
 * 않는다.
 */
@Service
public class ProductCardRegistrationFacade {
	private final ProductCardRegistrationService registrationService;
	private final FileImageService fileImageService;

	public ProductCardRegistrationFacade(ProductCardRegistrationService registrationService,
			FileImageService fileImageService) {
		this.registrationService = registrationService;
		this.fileImageService = fileImageService;
	}

	public CreateProductCardResponse register(Long userId, CreateProductCardRequest request) {
		RegisteredProductCard registered = registrationService.registerProductCard(userId, request);
		String imageFileUrl = fileImageService.findSignedUrl(registered.card().getImageFileId()).orElse(null);
		return CreateProductCardResponse.of(registered.card(), imageFileUrl, registered.boardIds(),
				registered.tags().stream().map(TagResponse::from).toList());
	}
}
