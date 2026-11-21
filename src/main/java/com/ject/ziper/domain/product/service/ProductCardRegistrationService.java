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

	// 잠금 순서: board:{userId} -> product-card:{userId} -> link:{uuid} ->
	// link-id:{id}. 역순으로 잡지 않는다.
	private static final String BOARD_LOCK_PREFIX = "board:";
	static final String PRODUCT_CARD_LOCK_PREFIX = "product-card:";
	private static final Pattern SCRIPT_OR_STYLE_BLOCK = Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");
	// 실제 태그·주석 형태만 제거한다. "S<M 사이즈 > L"처럼 평문 부등호는 속성 이름이 ASCII가 아니므로 태그로 보지 않는다.
	private static final Pattern HTML_TAG_PATTERN = Pattern.compile(
			"<!--.*?-->|</?[A-Za-z][A-Za-z0-9-]*"
					+ "(?:\\s+[A-Za-z_:][-A-Za-z0-9_:.]*(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s\"'=<>`]+))?)*\\s*/?>",
			Pattern.DOTALL);
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

	/**
	 * 카드·보드 연결·태그·미러링 작업을 한 트랜잭션으로 저장한다. 서명 URL 발급 같은 스토리지 호출은 이 트랜잭션 밖
	 * ({@link ProductCardRegistrationFacade})에서 하므로 서명이 실패해도 저장은 롤백되지 않는다.
	 */
	@Transactional
	public RegisteredProductCard registerProductCard(Long userId, CreateProductCardRequest request) {
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

		if (imageFileId != null) {
			fileImageService.requireOwnedFile(imageFileId, userId);
		}

		// 보드 삭제(ProductBoardCommandService.deleteBoard)와 같은 board:{userId} 잠금 아래에서 활성
		// 보드를
		// 검증하고 연결까지 마친다. 그러면 검증 직후 삭제된 보드에만 연결된 활성 카드가 생기지 않는다.
		advisoryLockRepository.lock(BOARD_LOCK_PREFIX + userId);
		advisoryLockRepository.lock(PRODUCT_CARD_LOCK_PREFIX + userId);
		verifyActiveOwnedBoards(userId, boardIds);

		if (productCardRepository.existsByUserIdAndProductUrl(userId, normalized.canonicalUrl())) {
			throw new BusinessException(ErrorCode.DUPLICATE_PRODUCT_URL);
		}

		// link-id 잠금을 잡은 채 최신 링크 상태를 읽는다. 상태 전파(ProductLinkService)와 직렬화되어 새 카드만
		// 이전 상태로 남지 않는다.
		ProductLink link = productLinkService.resolveOrCreateLink(normalized.canonicalUrl(), finalShop);
		List<Tag> resolvedTags = tagService.resolveOrCreateForCard(userId, request.tags());

		Instant now = clock.instant();
		// PENDING은 외부 이미지를 미러링할 때만 쓴다. 직접 업로드·대체 아이콘만 있으면 NONE이다.
		boolean mirrorCandidate = imageFileId == null && imageUrl != null;

		ProductCard card = productCardRepository.saveAndFlush(ProductCard.create(userId, normalized.canonicalUrl(),
				sharedUrl, finalShop, productTitle, price, memo, imageUrl, imageFileId, fallbackIcon, metadataSource,
				mirrorCandidate ? ProductImageStatus.PENDING : ProductImageStatus.NONE, link, now));

		List<ProductBoardCard> boardCards = boardIds.stream()
				.map(boardId -> ProductBoardCard.create(boardId, card.getId(), now)).toList();
		productBoardCardRepository.saveAll(boardCards);

		List<ProductCardTag> cardTags = resolvedTags.stream()
				.map(tag -> ProductCardTag.create(card.getId(), tag.getId(), now)).toList();
		productCardTagRepository.saveAll(cardTags);

		if (mirrorCandidate) {
			// 이미지 정책(허용 호스트·미러링 전략)은 사용자가 입력한 쇼핑몰 이름이 아니라 서버가 URL로 판정한 쇼핑몰로
			// 고른다. 미지원 몰은 null(일반 정책)이다.
			String policyShop = normalized.supported() ? normalized.shop() : null;
			boolean enqueued = imageMirrorService.enqueueMirrorJob(card.getId(), userId, imageUrl, policyShop);
			if (!enqueued) {
				card.updateImageStatus(ProductImageStatus.NONE, now);
				productCardRepository.saveAndFlush(card);
			}
		}

		return new RegisteredProductCard(card, boardIds, resolvedTags);
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
		advisoryLockRepository.lock(BOARD_LOCK_PREFIX + userId);
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
}
