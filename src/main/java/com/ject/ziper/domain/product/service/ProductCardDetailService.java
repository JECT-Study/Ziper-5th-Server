package com.ject.ziper.domain.product.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
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
import com.ject.ziper.global.persistence.AdvisoryLockRepository;

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
	private final AdvisoryLockRepository advisoryLockRepository;
	private final Clock clock;

	@Autowired
	public ProductCardDetailService(ProductCardRepository productCardRepository,
			ProductBoardCardRepository productBoardCardRepository, ProductCardTagRepository productCardTagRepository,
			ProductBoardRepository productBoardRepository, TagRepository tagRepository,
			UserCardEventRepository userCardEventRepository, ProductCardRegistrationService registrationService,
			ProductUrlNormalizer urlNormalizer, TagService tagService, FileImageService fileImageService,
			ImageMirrorService imageMirrorService, AdvisoryLockRepository advisoryLockRepository) {
		this(productCardRepository, productBoardCardRepository, productCardTagRepository, productBoardRepository,
				tagRepository, userCardEventRepository, registrationService, urlNormalizer, tagService,
				fileImageService, imageMirrorService, advisoryLockRepository, Clock.systemUTC());
	}

	public ProductCardDetailService(ProductCardRepository productCardRepository,
			ProductBoardCardRepository productBoardCardRepository, ProductCardTagRepository productCardTagRepository,
			ProductBoardRepository productBoardRepository, TagRepository tagRepository,
			UserCardEventRepository userCardEventRepository, ProductCardRegistrationService registrationService,
			ProductUrlNormalizer urlNormalizer, TagService tagService, FileImageService fileImageService,
			ImageMirrorService imageMirrorService, AdvisoryLockRepository advisoryLockRepository, Clock clock) {
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
		this.advisoryLockRepository = advisoryLockRepository;
		this.clock = clock;
	}

	/**
	 * 상세 조회 트랜잭션. 서명 URL 같은 스토리지 호출은 커밋 뒤 {@link ProductCardDetailFacade}가 한다.
	 */
	@Transactional
	public ProductCardDetailView getProductCardDetail(Long userId, Long cardId) {
		ProductCard card = lockAndFindActiveOwnedCard(userId, cardId);
		Instant now = clock.instant();
		card.resetRediscoveryCycle(now);
		productCardRepository.saveAndFlush(card);
		userCardEventRepository.save(UserCardEvent.create(userId, card.getId(), UserCardEventType.VIEW_DETAIL, now));

		List<TagResponse> tags = loadCardTags(card.getId());
		Set<Long> linkedBoardIds = productBoardCardRepository.findAllByCardIdOrderByIdAsc(card.getId()).stream()
				.map(ProductBoardCard::getBoardId).collect(Collectors.toSet());
		List<ProductCardBoardSummary> boards = loadActiveOwnedBoards(userId, linkedBoardIds);

		return new ProductCardDetailView(card, tags, boards);
	}

	@Transactional
	public OpenOriginalResponse openOriginalUrl(Long userId, Long cardId) {
		ProductCard card = lockAndFindActiveOwnedCard(userId, cardId);
		Instant now = clock.instant();
		card.resetRediscoveryCycle(now);
		productCardRepository.saveAndFlush(card);
		userCardEventRepository
				.save(UserCardEvent.create(userId, card.getId(), UserCardEventType.OPEN_ORIGINAL_URL, now));

		return OpenOriginalResponse.from(card);
	}

	/**
	 * 수정 트랜잭션. 서명 URL 조립은 커밋 뒤 {@link ProductCardDetailFacade}가 하므로 서명이 실패해도 수정은
	 * 롤백되지 않는다. 잠금 순서는 보드, 사용자 상품, 미러링 작업 행, 카드 행이다.
	 */
	@Transactional
	public ProductCardDetailView updateProductCard(Long userId, Long cardId, UpdateProductCardRequest request) {
		if (request == null) {
			throw new BusinessException(ErrorCode.INVALID_PRODUCT_CARD_INPUT);
		}
		validateCardId(cardId);
		// 카드 URL은 불변이므로 잠금 전에 URL만 읽어 입력 검증에 쓴다. 카드 엔티티는 잠금 뒤에 처음 읽는다.
		String productUrl = productCardRepository.findActiveProductUrl(cardId, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND));

		NormalizedProductUrl normalized = urlNormalizer.normalize(productUrl);
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

		if (imageFileId != null) {
			fileImageService.requireOwnedFile(imageFileId, userId);
		}
		List<Long> boardIds = registrationService.validateActiveOwnedBoardIds(userId, deduplicatedBoardIds);
		ProductCard card = lockForMutation(userId, cardId);

		Instant now = clock.instant();
		ProductImageStatus nextImageStatus = resolveUpdatedImageStatus(card, userId, finalShop, imageUrl, imageFileId);

		card.updateDetails(productTitle, price, memo, finalShop, imageUrl, imageFileId, fallbackIcon, nextImageStatus,
				now);
		productCardRepository.saveAndFlush(card);

		syncBoardMappings(card.getId(), boardIds, now);
		List<Tag> resolvedTags = tagService.resolveOrCreateForCard(userId, request.tags());
		syncTagMappings(card.getId(), resolvedTags, now);

		List<TagResponse> tags = sortTags(resolvedTags);
		List<ProductCardBoardSummary> boards = loadActiveOwnedBoards(userId, new LinkedHashSet<>(boardIds));

		return new ProductCardDetailView(card, tags, boards);
	}

	@Transactional
	public void deleteProductCard(Long userId, Long cardId) {
		validateCardId(cardId);
		productCardRepository.findActiveProductUrl(cardId, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND));
		ProductCard card = lockForMutation(userId, cardId);
		Instant now = clock.instant();
		imageMirrorService.cancelPendingMirrorJobs(card.getId());
		card.softDelete(now);
		productCardRepository.saveAndFlush(card);
	}

	private ProductCard lockForMutation(Long userId, Long cardId) {
		// 작업 행을 잠그기 전에 다른 카드 수정 요청의 새 미러링 작업 등록을 막는다.
		advisoryLockRepository.lock(ProductCardRegistrationService.PRODUCT_CARD_LOCK_PREFIX + userId);
		imageMirrorService.lockActiveJobsForCard(cardId);
		return lockAndFindActiveOwnedCard(userId, cardId);
	}

	private ProductCard lockAndFindActiveOwnedCard(Long userId, Long cardId) {
		validateCardId(cardId);
		return productCardRepository.findLockedByIdAndUserIdAndDeletedFalse(cardId, userId)
				.orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND));
	}

	private static void validateCardId(Long cardId) {
		if (cardId == null || cardId <= 0L) {
			throw new BusinessException(ErrorCode.PRODUCT_CARD_NOT_FOUND);
		}
	}

	/**
	 * 이미지가 바뀌면 기존 PENDING·PROCESSING 미러링 작업을 같은 트랜잭션에서 취소한다. 그래야 늦게 끝난 이전 작업이 수정한
	 * 이미지를 덮어쓰지 못한다. 상태 규칙은 등록 API와 같다. 직접 업로드·아이콘만 있으면 NONE, 외부 URL 미러링 대기는
	 * PENDING이다.
	 */
	private ProductImageStatus resolveUpdatedImageStatus(ProductCard card, Long userId, String resolvedShop,
			String imageUrl, Long imageFileId) {
		boolean imageChanged = !Objects.equals(imageUrl, card.getImageUrl())
				|| !Objects.equals(imageFileId, card.getImageFileId());
		boolean retryMirror = !imageChanged && imageFileId == null && imageUrl != null
				&& card.getImageStatus() == ProductImageStatus.NONE;
		if (!imageChanged && !retryMirror) {
			return card.getImageStatus();
		}
		imageMirrorService.cancelPendingMirrorJobs(card.getId());
		if (imageFileId != null || imageUrl == null) {
			return ProductImageStatus.NONE;
		}
		boolean enqueued = imageMirrorService.enqueueMirrorJob(card.getId(), userId, imageUrl, resolvedShop);
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
