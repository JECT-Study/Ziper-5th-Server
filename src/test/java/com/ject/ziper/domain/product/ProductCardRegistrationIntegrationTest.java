package com.ject.ziper.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.domain.board.entity.ProductBoard;
import com.ject.ziper.domain.board.repository.ProductBoardRepository;
import com.ject.ziper.domain.file.client.ExternalImageFetcher;
import com.ject.ziper.domain.file.client.ExternalImageFetcher.FetchResult;
import com.ject.ziper.domain.file.dto.ImageUploadResponse;
import com.ject.ziper.domain.file.entity.ImageMirrorJob;
import com.ject.ziper.domain.file.entity.ImageMirrorJobStatus;
import com.ject.ziper.domain.file.repository.FileAssetRepository;
import com.ject.ziper.domain.file.repository.ImageMirrorJobRepository;
import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.domain.file.service.ImageMirrorService;
import com.ject.ziper.domain.file.worker.ImageMirrorWorker;
import com.ject.ziper.domain.product.dto.CreateProductCardRequest;
import com.ject.ziper.domain.product.dto.CreateProductCardResponse;
import com.ject.ziper.domain.product.entity.FallbackIcon;
import com.ject.ziper.domain.product.entity.MetadataSource;
import com.ject.ziper.domain.product.entity.ProductBoardCard;
import com.ject.ziper.domain.product.entity.ProductCard;
import com.ject.ziper.domain.product.entity.ProductCardStatus;
import com.ject.ziper.domain.product.entity.ProductCardTag;
import com.ject.ziper.domain.product.entity.ProductImageStatus;
import com.ject.ziper.domain.product.extraction.ProductUrlNormalizer;
import com.ject.ziper.domain.product.link.entity.ProductLink;
import com.ject.ziper.domain.product.link.repository.ProductLinkRepository;
import com.ject.ziper.domain.product.link.service.ProductLinkService;
import com.ject.ziper.domain.product.repository.ProductBoardCardRepository;
import com.ject.ziper.domain.product.repository.ProductCardRepository;
import com.ject.ziper.domain.product.repository.ProductCardTagRepository;
import com.ject.ziper.domain.product.service.ProductCardRegistrationService;
import com.ject.ziper.domain.tag.service.TagService;
import com.ject.ziper.domain.user.entity.User;
import com.ject.ziper.domain.user.repository.UserRepository;
import com.ject.ziper.global.persistence.AdvisoryLockRepository;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;
import com.ject.ziper.global.storage.InMemoryObjectStorageClient;
import com.ject.ziper.global.storage.ObjectStorageClient;
import com.ject.ziper.global.storage.StorageProperties;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProductCardRegistrationIntegrationTest extends PostgresTestSupport {
	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private ProductBoardRepository productBoardRepository;

	@Autowired
	private ProductCardRepository productCardRepository;

	@Autowired
	private ProductBoardCardRepository productBoardCardRepository;

	@Autowired
	private ProductCardTagRepository productCardTagRepository;

	@Autowired
	private ProductLinkRepository productLinkRepository;

	@Autowired
	private ImageMirrorJobRepository imageMirrorJobRepository;

	@Autowired
	private FileAssetRepository fileAssetRepository;

	@Autowired
	private FileImageService fileImageService;

	@Autowired
	private ImageMirrorWorker imageMirrorWorker;

	@Autowired
	private ProductUrlNormalizer urlNormalizer;

	@Autowired
	private ProductLinkService productLinkService;

	@Autowired
	private TagService tagService;

	@Autowired
	private AdvisoryLockRepository advisoryLockRepository;

	@Autowired
	private ObjectStorageClient objectStorageClient;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@MockitoBean
	private ExternalImageFetcher externalImageFetcher;

	private User user;
	private ProductBoard defaultBoard;
	private ProductBoard customBoard;
	private String accessToken;

	@BeforeEach
	void setUp() {
		reset(externalImageFetcher);
		if (objectStorageClient instanceof InMemoryObjectStorageClient memoryStorage) {
			memoryStorage.clear();
		}
		imageMirrorJobRepository.deleteAll();
		jdbc.update("DELETE FROM ziper.product_link_reports");
		jdbc.update("DELETE FROM ziper.product_board_card");
		jdbc.update("DELETE FROM ziper.product_card_tags");
		jdbc.update("DELETE FROM ziper.product_cards");
		jdbc.update("DELETE FROM ziper.product_links");
		jdbc.update("DELETE FROM ziper.tags WHERE is_default = FALSE");
		jdbc.update("DELETE FROM ziper.product_boards");
		jdbc.update("DELETE FROM ziper.users");
		fileAssetRepository.deleteAll();

		user = userRepository
				.save(User.createEmailUser("card-owner@ziper.test", "{bcrypt}hash", "카드유저", false, Instant.now()));
		defaultBoard = productBoardRepository.save(ProductBoard.createDefault(user.getId()));
		customBoard = productBoardRepository.save(ProductBoard.createCustom(user.getId(), "봄 아우터"));
		accessToken = jwtTokenProvider.issueAccessToken(user.getId());
	}

	@Test
	void 지원쇼핑몰모바일URL과HTML상품명이주어지면_카드등록후미러링워커를실행하면_정규화저장과이미지READY전환이완료된다() throws Exception {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("productUrl", "https://m.musinsa.com/app/goods/9876543/0?utm_source=kakao&fbclid=xyz#reviews");
		payload.put("sharedUrl", "https://musinsa.onelink.me/abc123?utm_medium=share");
		payload.put("shop", "위조된쇼핑몰");
		payload.put("productTitle", " <script>alert('xss')</script><b>오버핏</b>   코튼\t티셔츠 ");
		payload.put("price", 39000);
		payload.put("memo", "  세일할 때 꼭 구매하기  ");
		payload.put("imageUrl", "https://image.msscdn.net/images/goods/9876543.jpg");
		payload.put("boardIds", List.of(defaultBoard.getId(), customBoard.getId(), defaultBoard.getId()));
		payload.put("tags", List.of("#봄", "데이트", "봄"));
		payload.put("metadataSource", "CLIENT");

		String responseBody = mvc
				.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
						.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.productUrl").value("https://www.musinsa.com/products/9876543"))
				.andExpect(jsonPath("$.sharedUrl").value("https://musinsa.onelink.me/abc123?utm_medium=share"))
				.andExpect(jsonPath("$.shop").value("무신사")).andExpect(jsonPath("$.productTitle").value("오버핏 코튼 티셔츠"))
				.andExpect(jsonPath("$.price").value(39000)).andExpect(jsonPath("$.memo").value("세일할 때 꼭 구매하기"))
				.andExpect(jsonPath("$.imageUrl").value("https://image.msscdn.net/images/goods/9876543.jpg"))
				.andExpect(jsonPath("$.imageFileId").doesNotExist())
				.andExpect(jsonPath("$.imageFileUrl").doesNotExist())
				.andExpect(jsonPath("$.fallbackIcon").doesNotExist())
				.andExpect(jsonPath("$.imageStatus").value("PENDING"))
				.andExpect(jsonPath("$.metadataSource").value("CLIENT"))
				.andExpect(jsonPath("$.status").value("PLANNED")).andExpect(jsonPath("$.link.status").value("UNKNOWN"))
				.andExpect(jsonPath("$.link.checkedAt").doesNotExist())
				.andExpect(jsonPath("$.link.source").doesNotExist()).andExpect(jsonPath("$.boardIds.length()").value(2))
				.andExpect(jsonPath("$.boardIds[0]").value(defaultBoard.getId()))
				.andExpect(jsonPath("$.boardIds[1]").value(customBoard.getId()))
				.andExpect(jsonPath("$.tags.length()").value(2)).andExpect(jsonPath("$.tags[0].title").value("봄"))
				.andExpect(jsonPath("$.tags[0].isDefault").value(true))
				.andExpect(jsonPath("$.tags[1].title").value("데이트"))
				.andExpect(jsonPath("$.tags[1].isDefault").value(false)).andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		JsonNode json = objectMapper.readTree(responseBody);
		long cardId = json.path("cardId").asLong();

		ProductCard savedCard = productCardRepository.findById(cardId).orElseThrow();
		assertThat(savedCard.getStatus()).isEqualTo(ProductCardStatus.PLANNED);
		assertThat(savedCard.getMetadataSource()).isEqualTo(MetadataSource.CLIENT);
		assertThat(savedCard.getImageStatus()).isEqualTo(ProductImageStatus.PENDING);
		assertThat(savedCard.getRediscoveryStage()).isZero();
		assertThat(Duration.between(savedCard.getLastInteractedAt(), savedCard.getNextRediscoveryAt()))
				.isEqualTo(Duration.ofDays(14));
		assertThat(savedCard.isDeleted()).isFalse();

		ProductLink link = productLinkRepository.findByCanonicalUrl("https://www.musinsa.com/products/9876543")
				.orElseThrow();
		assertThat(savedCard.getLinkId()).isEqualTo(link.getId());
		assertThat(link.getShop()).isEqualTo("무신사");

		List<ProductBoardCard> boardCards = productBoardCardRepository.findAllByCardIdOrderByIdAsc(cardId);
		assertThat(boardCards).extracting(ProductBoardCard::getBoardId).containsExactly(defaultBoard.getId(),
				customBoard.getId());

		List<ProductCardTag> cardTags = productCardTagRepository.findAllByCardIdOrderByIdAsc(cardId);
		assertThat(cardTags).hasSize(2);

		List<ImageMirrorJob> jobs = imageMirrorJobRepository.findByCardIdOrderByIdDesc(cardId);
		assertThat(jobs).singleElement().satisfies(job -> {
			assertThat(job.getStatus()).isEqualTo(ImageMirrorJobStatus.PENDING);
			assertThat(job.getSourceUrl()).isEqualTo("https://image.msscdn.net/images/goods/9876543.jpg");
			assertThat(job.getShop()).isEqualTo("무신사");
		});

		when(externalImageFetcher.fetch(eq("https://image.msscdn.net/images/goods/9876543.jpg"), any(), eq("무신사")))
				.thenReturn(FetchResult.ok(createJpeg(800, 800), "image/jpeg"));

		assertThat(imageMirrorWorker.processPendingJobs()).isEqualTo(1);

		ProductCard mirroredCard = productCardRepository.findById(cardId).orElseThrow();
		assertThat(mirroredCard.getImageFileId()).isNotNull();
		assertThat(mirroredCard.getImageStatus()).isEqualTo(ProductImageStatus.READY);
		assertThat(mirroredCard.getImageUrl()).isEqualTo("https://image.msscdn.net/images/goods/9876543.jpg");
	}

	@Test
	void 외부이미지미러링이실패하는상황에서_카드등록후워커를실행하면_이미지상태가FAILED로갱신되고원본URL이보존된다() throws Exception {
		String imageUrl = "https://image.msscdn.net/images/goods/blocked.jpg";
		Map<String, Object> payload = basePayload("https://www.musinsa.com/products/55555", "차단 상품", 19000, "CLIENT");
		payload.put("imageUrl", imageUrl);

		String body = mvc
				.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
						.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.imageStatus").value("PENDING")).andReturn()
				.getResponse().getContentAsString(StandardCharsets.UTF_8);
		long cardId = objectMapper.readTree(body).path("cardId").asLong();

		when(externalImageFetcher.fetch(eq(imageUrl), any(), eq("무신사")))
				.thenReturn(FetchResult.failure(403, "HTTP_403"));
		assertThat(imageMirrorWorker.processPendingJobs()).isEqualTo(1);

		ProductCard failedCard = productCardRepository.findById(cardId).orElseThrow();
		assertThat(failedCard.getImageFileId()).isNull();
		assertThat(failedCard.getImageStatus()).isEqualTo(ProductImageStatus.FAILED);
		assertThat(failedCard.getImageUrl()).isEqualTo(imageUrl);
	}

	@Test
	void 직접업로드한이미지파일ID로_상품카드를등록하면_READY상태와서명URL을반환하고미러링작업을생성하지않는다() throws Exception {
		byte[] jpeg = createJpeg(600, 600);
		ImageUploadResponse uploaded = fileImageService.uploadDirectImage(user.getId(), jpeg, "direct.jpg",
				"image/jpeg", jpeg.length);

		Map<String, Object> payload = basePayload("https://www.musinsa.com/products/11111", "직접 업로드 상품", 45000,
				"MANUAL");
		payload.put("imageFileId", uploaded.fileId());

		String body = mvc
				.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
						.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.imageFileId").value(uploaded.fileId()))
				.andExpect(jsonPath("$.imageFileUrl").isNotEmpty()).andExpect(jsonPath("$.imageStatus").value("READY"))
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		long cardId = objectMapper.readTree(body).path("cardId").asLong();
		assertThat(imageMirrorJobRepository.findByCardIdOrderByIdDesc(cardId)).isEmpty();
	}

	@Test
	void 대체아이콘만전달하고공백메모를입력했을때_상품카드를등록하면_NONE상태와null메모로저장된다() throws Exception {
		Map<String, Object> payload = basePayload("https://www.musinsa.com/products/22222", "대체 아이콘 상품", 0, "MANUAL");
		payload.put("fallbackIcon", "PANTS");
		payload.put("memo", "   ");

		String body = mvc
				.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
						.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.price").value(0))
				.andExpect(jsonPath("$.memo").doesNotExist()).andExpect(jsonPath("$.fallbackIcon").value("PANTS"))
				.andExpect(jsonPath("$.imageStatus").value("NONE")).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);

		long cardId = objectMapper.readTree(body).path("cardId").asLong();
		ProductCard saved = productCardRepository.findById(cardId).orElseThrow();
		assertThat(saved.getFallbackIcon()).isEqualTo(FallbackIcon.PANTS);
		assertThat(saved.getMemo()).isNull();
		assertThat(imageMirrorJobRepository.findByCardIdOrderByIdDesc(cardId)).isEmpty();
	}

	@Test
	void 미지원쇼핑몰의유효한HTTPS이미지URL과쇼핑몰명이주어지면_상품카드를등록하면_201과입력쇼핑몰명을저장한다() throws Exception {
		Map<String, Object> payload = basePayload("https://boutique.example.com/item/42?b=2&utm_source=ig&a=1#section",
				"핸드메이드 가방", 89000, "MANUAL");
		payload.put("shop", " 작은편집샵 ");
		payload.put("imageUrl", "https://cdn.boutique.example.com/images/42.png");

		mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.productUrl").value("https://boutique.example.com/item/42?a=1&b=2"))
				.andExpect(jsonPath("$.shop").value("작은편집샵"))
				.andExpect(jsonPath("$.imageUrl").value("https://cdn.boutique.example.com/images/42.png"))
				.andExpect(jsonPath("$.imageStatus").value("PENDING"));
	}

	@Test
	void 이미저장된상품이있을때_동일정규화URL을다른형태로재등록하면_같은유저는409를반환하고다른유저는공유링크로성공한다() throws Exception {
		Map<String, Object> first = basePayload("https://www.musinsa.com/products/77777", "무신사 자켓", 79000, "CLIENT");
		first.put("fallbackIcon", "TOP");

		mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(first)))
				.andExpect(status().isCreated());

		Map<String, Object> duplicate = basePayload("https://m.musinsa.com/app/goods/77777/0?utm_source=kakao", "중복 자켓",
				79000, "CLIENT");
		duplicate.put("fallbackIcon", "TOP");

		mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(duplicate)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_PRODUCT_URL"));

		User otherUser = userRepository
				.save(User.createEmailUser("other-owner@ziper.test", "{bcrypt}hash", "다른유저", false, Instant.now()));
		ProductBoard otherBoard = productBoardRepository.save(ProductBoard.createDefault(otherUser.getId()));
		String otherToken = jwtTokenProvider.issueAccessToken(otherUser.getId());

		Map<String, Object> otherPayload = basePayload("https://m.musinsa.com/products/77777?fbclid=abc", "다른 유저 자켓",
				79000, "CLIENT");
		otherPayload.put("fallbackIcon", "TOP");
		otherPayload.put("boardIds", List.of(otherBoard.getId()));

		mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(otherToken))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(otherPayload)))
				.andExpect(status().isCreated());

		assertThat(productLinkRepository.count()).isEqualTo(1L);
	}

	@Test
	void 미러링킬스위치가꺼져있을때_외부이미지URL로카드를등록하면_이미지상태가NONE이고미러링작업이생성되지않는다() {
		StorageProperties killSwitchOff = new StorageProperties("memory", "", "", "product-images", 900L,
				StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES, false, List.of(), false, 5000L, 5, 5000, 10000, 2, 0L, 3,
				300L, 30L, null, null, null, null, null, null, null);
		ImageMirrorService disabledMirrorService = new ImageMirrorService(imageMirrorJobRepository, killSwitchOff,
				imageMirrorWorker);
		ProductCardRegistrationService serviceWithKillSwitchOff = new ProductCardRegistrationService(
				productCardRepository, productBoardCardRepository, productCardTagRepository, productBoardRepository,
				urlNormalizer, productLinkService, tagService, fileImageService, disabledMirrorService,
				advisoryLockRepository);

		CreateProductCardRequest request = new CreateProductCardRequest("https://www.musinsa.com/products/88888", null,
				null, "킬스위치 상품", 15000L, null, "https://image.msscdn.net/images/goods/88888.jpg", null, null,
				List.of(defaultBoard.getId()), List.of("봄"), "CLIENT");

		CreateProductCardResponse response = serviceWithKillSwitchOff.registerProductCard(user.getId(), request);
		assertThat(response.imageStatus()).isEqualTo(ProductImageStatus.NONE);
		assertThat(imageMirrorJobRepository.findByCardIdOrderByIdDesc(response.cardId())).isEmpty();
	}

	@Test
	void 잘못된URL과이미지URL과필수값누락이주어지면_카드등록을요청하면_각오류코드로거부한다() throws Exception {
		// 1. 잘못된 URL / 비허용 스킴
		Map<String, Object> badScheme = basePayload("javascript:alert(1)", "상품", 10000, "MANUAL");
		badScheme.put("fallbackIcon", "TOP");
		expectError(badScheme, 400, "INVALID_PRODUCT_URL");

		// 2. 잘못된 sharedUrl
		Map<String, Object> badSharedUrl = basePayload("https://www.musinsa.com/products/1", "상품", 10000, "CLIENT");
		badSharedUrl.put("sharedUrl", "ftp://share.example.com/1");
		badSharedUrl.put("fallbackIcon", "TOP");
		expectError(badSharedUrl, 400, "INVALID_PRODUCT_URL");

		// 3. http:// imageUrl 거부
		Map<String, Object> httpImage = basePayload("https://boutique.example.com/p/1", "상품", 10000, "MANUAL");
		httpImage.put("imageUrl", "http://cdn.boutique.example.com/img.jpg");
		expectError(httpImage, 400, "INVALID_PRODUCT_IMAGE_URL");

		// 4. 지원몰의 비허용 imageHosts 거부
		Map<String, Object> disallowedHost = basePayload("https://www.musinsa.com/products/2", "상품", 10000, "CLIENT");
		disallowedHost.put("imageUrl", "https://evil.example.com/img.jpg");
		expectError(disallowedHost, 400, "INVALID_PRODUCT_IMAGE_URL");

		// 5. 카드 이미지 3종 모두 누락
		Map<String, Object> noImage = basePayload("https://www.musinsa.com/products/3", "상품", 10000, "MANUAL");
		expectError(noImage, 400, "INVALID_PRODUCT_CARD_INPUT");

		// 6. 가격 누락, 음수, 상한 초과
		Map<String, Object> nullPrice = basePayload("https://www.musinsa.com/products/4", "상품", null, "MANUAL");
		nullPrice.put("fallbackIcon", "TOP");
		expectError(nullPrice, 400, "INVALID_PRODUCT_CARD_INPUT");

		Map<String, Object> negativePrice = basePayload("https://www.musinsa.com/products/5", "상품", -1, "MANUAL");
		negativePrice.put("fallbackIcon", "TOP");
		expectError(negativePrice, 400, "INVALID_PRODUCT_CARD_INPUT");

		Map<String, Object> overMaxPrice = basePayload("https://www.musinsa.com/products/6", "상품", 1_000_000_001L,
				"MANUAL");
		overMaxPrice.put("fallbackIcon", "TOP");
		expectError(overMaxPrice, 400, "INVALID_PRODUCT_CARD_INPUT");

		// 7. HTML 태그만 있는 상품명, 201자 상품명, 101자 메모
		Map<String, Object> htmlOnlyTitle = basePayload("https://www.musinsa.com/products/7", "<b>  </b>", 10000,
				"CLIENT");
		htmlOnlyTitle.put("fallbackIcon", "TOP");
		expectError(htmlOnlyTitle, 400, "INVALID_PRODUCT_CARD_INPUT");

		Map<String, Object> longTitle = basePayload("https://www.musinsa.com/products/8", "가".repeat(201), 10000,
				"MANUAL");
		longTitle.put("fallbackIcon", "TOP");
		expectError(longTitle, 400, "INVALID_PRODUCT_CARD_INPUT");

		Map<String, Object> longMemo = basePayload("https://www.musinsa.com/products/9", "상품", 10000, "MANUAL");
		longMemo.put("fallbackIcon", "TOP");
		longMemo.put("memo", "메".repeat(101));
		expectError(longMemo, 400, "INVALID_PRODUCT_CARD_INPUT");

		// 8. 잘못된 fallbackIcon / metadataSource
		Map<String, Object> badIcon = basePayload("https://www.musinsa.com/products/10", "상품", 10000, "MANUAL");
		badIcon.put("fallbackIcon", "SHOES");
		expectError(badIcon, 400, "INVALID_FALLBACK_ICON");

		Map<String, Object> badSource = basePayload("https://www.musinsa.com/products/11", "상품", 10000, "SERVER");
		badSource.put("fallbackIcon", "TOP");
		expectError(badSource, 400, "INVALID_METADATA_SOURCE");

		// 9. 빈 보드 목록, 빈 태그 목록, 7자 태그
		Map<String, Object> emptyBoards = basePayload("https://www.musinsa.com/products/12", "상품", 10000, "MANUAL");
		emptyBoards.put("fallbackIcon", "TOP");
		emptyBoards.put("boardIds", List.of());
		expectError(emptyBoards, 400, "INVALID_BOARD_SELECTION");

		Map<String, Object> emptyTags = basePayload("https://www.musinsa.com/products/13", "상품", 10000, "MANUAL");
		emptyTags.put("fallbackIcon", "TOP");
		emptyTags.put("tags", List.of());
		expectError(emptyTags, 400, "INVALID_TAG_TITLE");

		Map<String, Object> longTag = basePayload("https://www.musinsa.com/products/14", "상품", 10000, "MANUAL");
		longTag.put("fallbackIcon", "TOP");
		longTag.put("tags", List.of("일곱글자태그임"));
		expectError(longTag, 400, "INVALID_TAG_TITLE");
	}

	@Test
	void 존재하지않거나삭제되거나타인소유인보드와미존재파일ID로_카드등록을요청하면_404오류를반환한다() throws Exception {
		ProductBoard deletedBoard = ProductBoard.createCustom(user.getId(), "삭제된 보드");
		deletedBoard.softDelete(Instant.now());
		productBoardRepository.save(deletedBoard);

		User otherUser = userRepository
				.save(User.createEmailUser("board-other@ziper.test", "{bcrypt}hash", "타인보드", false, Instant.now()));
		ProductBoard otherBoard = productBoardRepository.save(ProductBoard.createDefault(otherUser.getId()));

		Map<String, Object> withDeletedBoard = basePayload("https://www.musinsa.com/products/101", "상품", 10000,
				"MANUAL");
		withDeletedBoard.put("fallbackIcon", "TOP");
		withDeletedBoard.put("boardIds", List.of(deletedBoard.getId()));
		expectError(withDeletedBoard, 404, "BOARD_NOT_FOUND");

		Map<String, Object> withOtherBoard = basePayload("https://www.musinsa.com/products/102", "상품", 10000, "MANUAL");
		withOtherBoard.put("fallbackIcon", "TOP");
		withOtherBoard.put("boardIds", List.of(otherBoard.getId()));
		expectError(withOtherBoard, 404, "BOARD_NOT_FOUND");

		Map<String, Object> withMissingFile = basePayload("https://www.musinsa.com/products/103", "상품", 10000,
				"MANUAL");
		withMissingFile.put("imageFileId", 999999L);
		expectError(withMissingFile, 404, "FILE_NOT_FOUND");
	}

	@Test
	void 인증토큰없이_상품카드등록을요청하면_401오류를반환한다() throws Exception {
		Map<String, Object> payload = basePayload("https://www.musinsa.com/products/200", "상품", 10000, "MANUAL");
		payload.put("fallbackIcon", "TOP");

		mvc.perform(post("/api/v1/products").contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(payload))).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	private void expectError(Map<String, Object> payload, int expectedStatus, String expectedCode) throws Exception {
		mvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
				.andExpect(status().is(expectedStatus)).andExpect(jsonPath("$.code").value(expectedCode));
	}

	private Map<String, Object> basePayload(String productUrl, String productTitle, Object price,
			String metadataSource) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("productUrl", productUrl);
		payload.put("productTitle", productTitle);
		payload.put("price", price);
		payload.put("boardIds", List.of(defaultBoard.getId()));
		payload.put("tags", List.of("봄"));
		payload.put("metadataSource", metadataSource);
		return payload;
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private static byte[] createJpeg(int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(Color.DARK_GRAY);
			graphics.fillRect(0, 0, width, height);
		} finally {
			graphics.dispose();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}
}
