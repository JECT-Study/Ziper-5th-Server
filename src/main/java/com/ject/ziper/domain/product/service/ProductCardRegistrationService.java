package com.ject.ziper.domain.product.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.board.entity.ProductBoard;
import com.ject.ziper.domain.board.repository.ProductBoardRepository;
import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.domain.file.service.ImageMirrorService;
import com.ject.ziper.domain.product.dto.CreateProductCardRequest;
import com.ject.ziper.domain.product.dto.CreateProductCardResponse;
import com.ject.ziper.domain.product.entity.FallbackIcon;
import com.ject.ziper.domain.product.entity.MetadataSource;
import com.ject.ziper.domain.product.entity.ProductBoardCard;
import com.ject.ziper.domain.product.entity.ProductCard;
import com.ject.ziper.domain.product.entity.ProductCardTag;
import com.ject.ziper.domain.product.entity.ProductImageStatus;
import com.ject.ziper.domain.product.extraction.NormalizedProductUrl;
import com.ject.ziper.domain.product.extraction.ProductUrlNormalizer;
import com.ject.ziper.domain.product.link.entity.ProductLink;
import com.ject.ziper.domain.product.link.service.ProductLinkService;
import com.ject.ziper.domain.product.repository.ProductBoardCardRepository;
import com.ject.ziper.domain.product.repository.ProductCardRepository;
import com.ject.ziper.domain.product.repository.ProductCardTagRepository;
import com.ject.ziper.domain.tag.dto.TagResponse;
import com.ject.ziper.domain.tag.entity.Tag;
import com.ject.ziper.domain.tag.service.TagService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.persistence.AdvisoryLockRepository;

@Service
public class ProductCardRegistrationService {
	public static final int MAX_TITLE_LENGTH = 200;
	public static final int MAX_MEMO_LENGTH = 100;
	public static final int MAX_SHOP_LENGTH = 100;
	public static final long MAX_PRICE = 1_000_000_000L;

	private static final String LOCK_PREFIX = "product-card:";
	private static final Pattern SCRIPT_OR_STYLE_BLOCK = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");
	private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]*>");
	private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

	private final ProductCardRepository productCardRepository;
	private final ProductBoardCardRepository productBoardCardRepository;
	private final ProductCardTagRepository productCardTagRepository;
	private final ProductBoardRepository productBoardRepository;
	private final ProductUrlNormalizer urlNormalizer;
	private final ProductLinkService productLinkService;
	private final TagService tagService;
	private final FileImageService fileImageService;
	private final ImageMirrorService imageMirrorService;
	private final AdvisoryLockRepository advisoryLockRepository;
	private final Clock clock;

	@Autowired
	public ProductCardRegistrationService(ProductCardRepository productCardRepository,
			ProductBoardCardRepository productBoardCardRepository, ProductCardTagRepository productCardTagRepository,
			ProductBoardRepository productBoardRepository, ProductUrlNormalizer urlNormalizer,
			ProductLinkService productLinkService, TagService tagService, FileImageService fileImageService,
			ImageMirrorService imageMirrorService, AdvisoryLockRepository advisoryLockRepository) {
		this(productCardRepository, productBoardCardRepository, productCardTagRepository, productBoardRepository,
				urlNormalizer, productLinkService, tagService, fileImageService, imageMirrorService,
				advisoryLockRepository, Clock.systemUTC());
	}

	public ProductCardRegistrationService(ProductCardRepository productCardRepository,
			ProductBoardCardRepository productBoardCardRepository, ProductCardTagRepository productCardTagRepository,
			ProductBoardRepository productBoardRepository, ProductUrlNormalizer urlNormalizer,
			ProductLinkService productLinkService, TagService tagService, FileImageService fileImageService,
			ImageMirrorService imageMirrorService, AdvisoryLockRepository advisoryLockRepository, Clock clock) {
		this.productCardRepository = productCardRepository;
		this.productBoardCardRepository = productBoardCardRepository;
		this.productCardTagRepository = productCardTagRepository;
		this.productBoardRepository = productBoardRepository;
		this.urlNormalizer = urlNormalizer;
		this.productLinkService = productLinkService;
		this.tagService = tagService;
		this.fileImageService = fileImageService;
		this.imageMirrorService = imageMirrorService;
		this.advisoryLockRepository = advisoryLockRepository;
		this.clock = clock;
	}

	@Transactional
	public CreateProductCardResponse registerProductCard(Long userId, CreateProductCardRequest request) {
		if (request == null) {
			throw new BusinessException(ErrorCode.INVALID_PRODUCT_CARD_INPUT);
		}

		NormalizedProductUrl normalized = urlNormalizer.normalize(request.productUrl());
		String sharedUrl = validateOptionalSharedUrl(request.sharedUrl());
		String finalShop = resolveShop(normalized, request.shop());
		String productTitle = sanitizeAndValidateTitle(request.productTitle());
		long price = validatePrice(request.price());
		String memo = normalizeAndValidateMemo(request.memo());
		MetadataSource metadataSource = parseMetadataSource(request.metadataSource());
		FallbackIcon fallbackIcon = parseOptionalFallbackIcon(request.fallbackIcon());
		String imageUrl = normalizeAndValidateImageUrl(request.imageUrl(), normalized);
		Long imageFileId = request.imageFileId();
		validateImagePresence(imageUrl, imageFileId, fallbackIcon);

		List<Long> boardIds = deduplicateBoardIds(request.boardIds());
		validateTagInputs(request.tags());

		if (imageFileId != null && !fileImageService.exists(imageFileId)) {
			throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
		}
		verifyActiveOwnedBoards(userId, boardIds);

		advisoryLockRepository.lock(LOCK_PREFIX + userId);

		if (productCardRepository.existsByUserIdAndProductUrl(userId, normalized.canonicalUrl())) {
			throw new BusinessException(ErrorCode.DUPLICATE_PRODUCT_URL);
		}

		ProductLink link = productLinkService.resolveOrCreateLink(normalized.canonicalUrl(), finalShop);
		List<Tag> resolvedTags = tagService.resolveOrCreateForCard(userId, request.tags());

		Instant now = clock.instant();
		ProductImageStatus initialImageStatus = determineInitialImageStatus(imageFileId, imageUrl);

		ProductCard card = productCardRepository.saveAndFlush(
				ProductCard.create(userId, normalized.canonicalUrl(), sharedUrl, finalShop, productTitle, price, memo,
						imageUrl, imageFileId, fallbackIcon, metadataSource, initialImageStatus, link, now));

		List<ProductBoardCard> boardCards = boardIds.stream()
				.map(boardId -> ProductBoardCard.create(boardId, card.getId(), now)).toList();
		productBoardCardRepository.saveAll(boardCards);

		List<ProductCardTag> cardTags = resolvedTags.stream()
				.map(tag -> ProductCardTag.create(card.getId(), tag.getId(), now)).toList();
		productCardTagRepository.saveAll(cardTags);

		if (imageFileId == null && imageUrl != null) {
			boolean enqueued = imageMirrorService.enqueueMirrorJob(card.getId(), userId, imageUrl, finalShop);
			if (!enqueued) {
				card.updateImageStatus(ProductImageStatus.NONE, now);
				productCardRepository.saveAndFlush(card);
			}
		}

		String imageFileUrl = fileImageService.findSignedUrl(card.getImageFileId()).orElse(null);
		List<TagResponse> tagResponses = resolvedTags.stream().map(TagResponse::from).toList();
		return CreateProductCardResponse.of(card, imageFileUrl, boardIds, tagResponses);
	}

	public String validateOptionalSharedUrl(String rawSharedUrl) {
		if (rawSharedUrl == null || rawSharedUrl.isBlank()) {
			return null;
		}
		String trimmed = rawSharedUrl.trim();
		urlNormalizer.normalize(trimmed);
		return trimmed;
	}

	public String normalizeAndValidateImageUrl(String rawImageUrl, NormalizedProductUrl normalized) {
		if (rawImageUrl == null || rawImageUrl.isBlank()) {
			return null;
		}
		String trimmed = rawImageUrl.trim();
		if (!urlNormalizer.isValidHttpsUrl(trimmed)) {
			throw new BusinessException(ErrorCode.INVALID_PRODUCT_IMAGE_URL);
		}
		if (normalized.supported() && !urlNormalizer.isAllowedImageHost(normalized.shop(), trimmed)) {
			throw new BusinessException(ErrorCode.INVALID_PRODUCT_IMAGE_URL);
		}
		return trimmed;
	}

	public List<Long> validateActiveOwnedBoardIds(Long userId, List<Long> rawBoardIds) {
		List<Long> boardIds = deduplicateBoardIds(rawBoardIds);
		verifyActiveOwnedBoards(userId, boardIds);
		return boardIds;
	}

	public static String resolveShop(NormalizedProductUrl normalized, String rawShop) {
		if (normalized.supported()) {
			return normalized.shop();
		}
		return normalizeOptionalShop(rawShop);
	}

	public static String normalizeOptionalShop(String rawShop) {
		if (rawShop == null || rawShop.isBlank()) {
			return null;
		}
		String trimmed = rawShop.strip();
		int codePoints = trimmed.codePointCount(0, trimmed.length());
		if (codePoints > MAX_SHOP_LENGTH || trimmed.length() > MAX_SHOP_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_PRODUCT_CARD_INPUT);
		}
		return trimmed;
	}

	public static String sanitizeAndValidateTitle(String rawTitle) {
		if (rawTitle == null) {
			throw new BusinessException(ErrorCode.INVALID_PRODUCT_CARD_INPUT);
		}
		String current = rawTitle;
		while (true) {
			String withoutBlocks = SCRIPT_OR_STYLE_BLOCK.matcher(current).replaceAll(" ");
			String withoutTags = HTML_TAG_PATTERN.matcher(withoutBlocks).replaceAll("");
			if (withoutTags.equals(current)) {
				break;
			}
			current = withoutTags;
		}
		String normalized = WHITESPACE_PATTERN.matcher(current).replaceAll(" ").strip();
		int codePoints = normalized.codePointCount(0, normalized.length());
		if (normalized.isEmpty() || codePoints > MAX_TITLE_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_PRODUCT_CARD_INPUT);
		}
		return normalized;
	}

	public static long validatePrice(Long price) {
		if (price == null || price < 0L || price > MAX_PRICE) {
			throw new BusinessException(ErrorCode.INVALID_PRODUCT_CARD_INPUT);
		}
		return price;
	}

	public static String normalizeAndValidateMemo(String rawMemo) {
		if (rawMemo == null) {
			return null;
		}
		String trimmed = rawMemo.strip();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (trimmed.codePointCount(0, trimmed.length()) > MAX_MEMO_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_PRODUCT_CARD_INPUT);
		}
		return trimmed;
	}

	public static MetadataSource parseMetadataSource(String rawMetadataSource) {
		return MetadataSource.fromValue(rawMetadataSource)
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_METADATA_SOURCE));
	}

	public static FallbackIcon parseOptionalFallbackIcon(String rawFallbackIcon) {
		if (rawFallbackIcon == null || rawFallbackIcon.isBlank()) {
			return null;
		}
		return FallbackIcon.fromValue(rawFallbackIcon)
				.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_FALLBACK_ICON));
	}

	public static void validateImagePresence(String imageUrl, Long imageFileId, FallbackIcon fallbackIcon) {
		if (imageUrl == null && imageFileId == null && fallbackIcon == null) {
			throw new BusinessException(ErrorCode.INVALID_PRODUCT_CARD_INPUT);
		}
	}

	public static List<Long> deduplicateBoardIds(List<Long> rawBoardIds) {
		if (rawBoardIds == null || rawBoardIds.isEmpty()) {
			throw new BusinessException(ErrorCode.INVALID_BOARD_SELECTION);
		}
		Set<Long> uniqueIds = new LinkedHashSet<>();
		for (Long boardId : rawBoardIds) {
			if (boardId == null || boardId <= 0L) {
				throw new BusinessException(ErrorCode.INVALID_BOARD_SELECTION);
			}
			uniqueIds.add(boardId);
		}
		return new ArrayList<>(uniqueIds);
	}

	private void verifyActiveOwnedBoards(Long userId, List<Long> boardIds) {
		Set<Long> activeOwnedBoardIds = productBoardRepository.findAllByUserId(userId).stream()
				.filter(board -> !board.isDeleted()).map(ProductBoard::getId).collect(Collectors.toSet());
		for (Long boardId : boardIds) {
			if (!activeOwnedBoardIds.contains(boardId)) {
				throw new BusinessException(ErrorCode.BOARD_NOT_FOUND);
			}
		}
	}

	private static void validateTagInputs(List<String> rawTags) {
		if (rawTags == null || rawTags.isEmpty()) {
			throw new BusinessException(ErrorCode.INVALID_TAG_TITLE);
		}
		for (String rawTag : rawTags) {
			TagService.normalizeTitle(rawTag);
		}
	}

	private static ProductImageStatus determineInitialImageStatus(Long imageFileId, String imageUrl) {
		if (imageFileId != null) {
			return ProductImageStatus.READY;
		}
		if (imageUrl != null) {
			return ProductImageStatus.PENDING;
		}
		return ProductImageStatus.NONE;
	}
}
