package com.ject.ziper.domain.product.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.board.entity.ProductBoard;
import com.ject.ziper.domain.board.repository.ProductBoardRepository;
import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.domain.file.service.ImageMirrorService;
import com.ject.ziper.domain.product.dto.OpenOriginalResponse;
import com.ject.ziper.domain.product.dto.ProductCardBoardSummary;
import com.ject.ziper.domain.product.dto.ProductCardDetailResponse;
import com.ject.ziper.domain.product.dto.UpdateProductCardRequest;
import com.ject.ziper.domain.product.entity.FallbackIcon;
import com.ject.ziper.domain.product.entity.ProductBoardCard;
import com.ject.ziper.domain.product.entity.ProductCard;
import com.ject.ziper.domain.product.entity.ProductCardTag;
import com.ject.ziper.domain.product.entity.ProductImageStatus;
import com.ject.ziper.domain.product.entity.UserCardEvent;
import com.ject.ziper.domain.product.entity.UserCardEventType;
import com.ject.ziper.domain.product.extraction.NormalizedProductUrl;
import com.ject.ziper.domain.product.extraction.ProductUrlNormalizer;
import com.ject.ziper.domain.product.repository.ProductBoardCardRepository;
import com.ject.ziper.domain.product.repository.ProductCardRepository;
import com.ject.ziper.domain.product.repository.ProductCardTagRepository;
import com.ject.ziper.domain.product.repository.UserCardEventRepository;
import com.ject.ziper.domain.tag.dto.TagResponse;
import com.ject.ziper.domain.tag.entity.Tag;
import com.ject.ziper.domain.tag.repository.TagRepository;
import com.ject.ziper.domain.tag.service.TagService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

@Service
public class ProductCardDetailService {
	private final ProductCardRepository productCardRepository;
	private final ProductBoardCardRepository productBoardCardRepository;
	private final ProductCardTagRepository productCardTagRepository;
	private final ProductBoardRepository productBoardRepository;
	private final TagRepository tagRepository;
	private final UserCardEventRepository userCardEventRepository;
	private final ProductCardRegistrationService registrationService;
	private final ProductUrlNormalizer urlNormalizer;
	private final TagService tagService;
	private final FileImageService fileImageService;
	private final ImageMirrorService imageMirrorService;
	private final Clock clock;

	@Autowired
	public ProductCardDetailService(ProductCardRepository productCardRepository,
			ProductBoardCardRepository productBoardCardRepository, ProductCardTagRepository productCardTagRepository,
			ProductBoardRepository productBoardRepository, TagRepository tagRepository,
			UserCardEventRepository userCardEventRepository, ProductCardRegistrationService registrationService,
			ProductUrlNormalizer urlNormalizer, TagService tagService, FileImageService fileImageService,
			ImageMirrorService imageMirrorService) {
		this(productCardRepository, productBoardCardRepository, productCardTagRepository, productBoardRepository,
				tagRepository, userCardEventRepository, registrationService, urlNormalizer, tagService,
				fileImageService, imageMirrorService, Clock.systemUTC());
	}

	public ProductCardDetailService(ProductCardRepository productCardRepository,
			ProductBoardCardRepository productBoardCardRepository, ProductCardTagRepository productCardTagRepository,
			ProductBoardRepository productBoardRepository, TagRepository tagRepository,
			UserCardEventRepository userCardEventRepository, ProductCardRegistrationService registrationService,
			ProductUrlNormalizer urlNormalizer, TagService tagService, FileImageService fileImageService,
			ImageMirrorService imageMirrorService, Clock clock) {
		this.productCardRepository = productCardRepository;
		this.productBoardCardRepository = productBoardCardRepository;
		this.productCardTagRepository = productCardTagRepository;
		this.productBoardRepository = productBoardRepository;
		this.tagRepository = tagRepository;
		this.userCardEventRepository = userCardEventRepository;
		this.registrationService = registrationService;
		this.urlNormalizer = urlNormalizer;
		this.tagService = tagService;
		this.fileImageService = fileImageService;
		this.imageMirrorService = imageMirrorService;
		this.clock = clock;
	}

	@Transactional
	public ProductCardDetailResponse getProductCardDetail(Long userId, Long cardId) {
		ProductCard card = findActiveOwnedCard(userId, cardId);
		Instant now = clock.instant();
		card.resetRediscoveryCycle(now);
		productCardRepository.saveAndFlush(card);
		userCardEventRepository.save(UserCardEvent.create(userId, card.getId(), UserCardEventType.VIEW_DETAIL, now));

		List<TagResponse> tags = loadCardTags(card.getId());
		Set<Long> linkedBoardIds = productBoardCardRepository.findAllByCardIdOrderByIdAsc(card.getId()).stream()
				.map(ProductBoardCard::getBoardId).collect(Collectors.toSet());
		List<ProductCardBoardSummary> boards = loadActiveOwnedBoards(userId, linkedBoardIds);
		String imageFileUrl = fileImageService.findSignedUrl(card.getImageFileId()).orElse(null);

		return ProductCardDetailResponse.of(card, imageFileUrl, tags, boards);
	}

	@Transactional
	public OpenOriginalResponse openOriginalUrl(Long userId, Long cardId) {
		ProductCard card = findActiveOwnedCard(userId, cardId);
		Instant now = clock.instant();
		card.resetRediscoveryCycle(now);
		productCardRepository.saveAndFlush(card);
		userCardEventRepository
				.save(UserCardEvent.create(userId, card.getId(), UserCardEventType.OPEN_ORIGINAL_URL, now));

		return OpenOriginalResponse.from(card);
	}

	@Transactional
	public ProductCardDetailResponse updateProductCard(Long userId, Long cardId, UpdateProductCardRequest request) {
		if (request == null) {
			throw new BusinessException(ErrorCode.INVALID_PRODUCT_CARD_INPUT);
		}
		ProductCard card = findActiveOwnedCard(userId, cardId);

		NormalizedProductUrl normalized = urlNormalizer.normalize(card.getProductUrl());
		String finalShop = ProductCardRegistrationService.resolveShop(normalized, request.shop());
		String productTitle = ProductCardRegistrationService.sanitizeAndValidateTitle(request.productTitle());
		long price = ProductCardRegistrationService.validatePrice(request.price());
		String memo = ProductCardRegistrationService.normalizeAndValidateMemo(request.memo());
		FallbackIcon fallbackIcon = ProductCardRegistrationService.parseOptionalFallbackIcon(request.fallbackIcon());
		String imageUrl = registrationService.normalizeAndValidateImageUrl(request.imageUrl(), normalized);
		Long imageFileId = request.imageFileId();
		ProductCardRegistrationService.validateImagePresence(imageUrl, imageFileId, fallbackIcon);

		List<Long> deduplicatedBoardIds = ProductCardRegistrationService.deduplicateBoardIds(request.boardIds());
		validateTagInputs(request.tags());

		if (imageFileId != null && !fileImageService.exists(imageFileId)) {
			throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
		}
		List<Long> boardIds = registrationService.validateActiveOwnedBoardIds(userId, deduplicatedBoardIds);

		Instant now = clock.instant();
		ProductImageStatus nextImageStatus = resolveUpdatedImageStatus(card.getId(), userId, finalShop, imageUrl,
				imageFileId, card.getImageUrl(), card.getImageFileId(), card.getImageStatus());

		card.updateDetails(productTitle, price, memo, finalShop, imageUrl, imageFileId, fallbackIcon, nextImageStatus,
				now);
		productCardRepository.saveAndFlush(card);

		syncBoardMappings(card.getId(), boardIds, now);
		List<Tag> resolvedTags = tagService.resolveOrCreateForCard(userId, request.tags());
		syncTagMappings(card.getId(), resolvedTags, now);

		String imageFileUrl = fileImageService.findSignedUrl(card.getImageFileId()).orElse(null);
		List<TagResponse> tags = sortTags(resolvedTags);
		List<ProductCardBoardSummary> boards = loadActiveOwnedBoards(userId, new LinkedHashSet<>(boardIds));

		return ProductCardDetailResponse.of(card, imageFileUrl, tags, boards);
	}

	@Transactional
	public void deleteProductCard(Long userId, Long cardId) {
		ProductCard card = findActiveOwnedCard(userId, cardId);
		Instant now = clock.instant();
		card.softDelete(now);
		productCardRepository.saveAndFlush(card);
	}

	private ProductCard findActiveOwnedCard(Long userId, Long cardId) {
		if (cardId == null || cardId <= 0L) {
			throw new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND);
		}
		return productCardRepository.findByIdAndUserIdAndDeletedFalse(cardId, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND));
	}

	private ProductImageStatus resolveUpdatedImageStatus(Long cardId, Long userId, String resolvedShop, String imageUrl,
			Long imageFileId, String previousImageUrl, Long previousImageFileId,
			ProductImageStatus previousImageStatus) {
		if (imageFileId != null) {
			return ProductImageStatus.READY;
		}
		if (imageUrl == null) {
			return ProductImageStatus.NONE;
		}
		boolean needsMirror = !imageUrl.equals(previousImageUrl) || previousImageFileId != null
				|| previousImageStatus == ProductImageStatus.NONE;
		if (!needsMirror) {
			return previousImageStatus;
		}
		boolean enqueued = imageMirrorService.enqueueMirrorJob(cardId, userId, imageUrl, resolvedShop);
		return enqueued ? ProductImageStatus.PENDING : ProductImageStatus.NONE;
	}

	private void syncBoardMappings(Long cardId, List<Long> boardIds, Instant now) {
		List<ProductBoardCard> existingLinks = productBoardCardRepository.findAllByCardIdOrderByIdAsc(cardId);
		Set<Long> desiredBoardIds = new LinkedHashSet<>(boardIds);
		Set<Long> existingBoardIds = existingLinks.stream().map(ProductBoardCard::getBoardId)
				.collect(Collectors.toSet());

		List<ProductBoardCard> toRemove = existingLinks.stream()
				.filter(link -> !desiredBoardIds.contains(link.getBoardId())).toList();
		if (!toRemove.isEmpty()) {
			productBoardCardRepository.deleteAll(toRemove);
			productBoardCardRepository.flush();
		}

		List<ProductBoardCard> toAdd = boardIds.stream().filter(boardId -> !existingBoardIds.contains(boardId))
				.map(boardId -> ProductBoardCard.create(boardId, cardId, now)).toList();
		if (!toAdd.isEmpty()) {
			productBoardCardRepository.saveAll(toAdd);
			productBoardCardRepository.flush();
		}
	}

	private void syncTagMappings(Long cardId, List<Tag> resolvedTags, Instant now) {
		List<ProductCardTag> existingLinks = productCardTagRepository.findAllByCardIdOrderByIdAsc(cardId);
		Set<Long> desiredTagIds = resolvedTags.stream().map(Tag::getId)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Set<Long> existingTagIds = existingLinks.stream().map(ProductCardTag::getTagId).collect(Collectors.toSet());

		List<ProductCardTag> toRemove = existingLinks.stream().filter(link -> !desiredTagIds.contains(link.getTagId()))
				.toList();
		if (!toRemove.isEmpty()) {
			productCardTagRepository.deleteAll(toRemove);
			productCardTagRepository.flush();
		}

		List<ProductCardTag> toAdd = resolvedTags.stream().filter(tag -> !existingTagIds.contains(tag.getId()))
				.map(tag -> ProductCardTag.create(cardId, tag.getId(), now)).toList();
		if (!toAdd.isEmpty()) {
			productCardTagRepository.saveAll(toAdd);
			productCardTagRepository.flush();
		}
	}

	private List<TagResponse> loadCardTags(Long cardId) {
		List<Long> tagIds = productCardTagRepository.findAllByCardIdOrderByIdAsc(cardId).stream()
				.map(ProductCardTag::getTagId).toList();
		if (tagIds.isEmpty()) {
			return List.of();
		}
		return sortTags(tagRepository.findAllById(tagIds));
	}

	private List<ProductCardBoardSummary> loadActiveOwnedBoards(Long userId, Set<Long> linkedBoardIds) {
		if (linkedBoardIds.isEmpty()) {
			return List.of();
		}
		return productBoardRepository.findAllByUserId(userId).stream()
				.filter(board -> !board.isDeleted() && linkedBoardIds.contains(board.getId()))
				.sorted(Comparator.comparing(ProductBoard::getCreatedAt, Comparator.reverseOrder())
						.thenComparing(ProductBoard::getId, Comparator.reverseOrder()))
				.map(ProductCardBoardSummary::from).toList();
	}

	private static List<TagResponse> sortTags(Collection<Tag> tags) {
		return tags.stream().sorted(Comparator.comparing(Tag::isDefault).reversed().thenComparing(Tag::getId))
				.map(TagResponse::from).toList();
	}

	private static void validateTagInputs(List<String> rawTags) {
		if (rawTags == null || rawTags.isEmpty()) {
			throw new BusinessException(ErrorCode.INVALID_TAG_TITLE);
		}
		for (String rawTag : rawTags) {
			TagService.normalizeTitle(rawTag);
		}
	}
}
