package com.ject.ziper.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import com.ject.ziper.domain.file.dto.ImageUploadResponse;
import com.ject.ziper.domain.file.entity.ImageMirrorJob;
import com.ject.ziper.domain.file.entity.ImageMirrorJobStatus;
import com.ject.ziper.domain.file.repository.FileAssetRepository;
import com.ject.ziper.domain.file.repository.ImageMirrorJobRepository;
import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.domain.product.entity.ProductBoardCard;
import com.ject.ziper.domain.product.entity.ProductCard;
import com.ject.ziper.domain.product.entity.ProductCardStatus;
import com.ject.ziper.domain.product.entity.ProductImageStatus;
import com.ject.ziper.domain.product.entity.UserCardEvent;
import com.ject.ziper.domain.product.entity.UserCardEventType;
import com.ject.ziper.domain.product.repository.ProductBoardCardRepository;
import com.ject.ziper.domain.product.repository.ProductCardRepository;
import com.ject.ziper.domain.product.repository.UserCardEventRepository;
import com.ject.ziper.domain.user.entity.User;
import com.ject.ziper.domain.user.repository.UserRepository;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;
import com.ject.ziper.global.storage.InMemoryObjectStorageClient;
import com.ject.ziper.global.storage.ObjectStorageClient;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProductCardDetailIntegrationTest extends PostgresTestSupport {
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
	private UserCardEventRepository userCardEventRepository;

	@Autowired
	private ImageMirrorJobRepository imageMirrorJobRepository;

	@Autowired
	private FileAssetRepository fileAssetRepository;

	@Autowired
	private FileImageService fileImageService;

	@Autowired
	private ObjectStorageClient objectStorageClient;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@MockitoBean
	private ExternalImageFetcher externalImageFetcher;

	private User owner;
	private User otherUser;
	private ProductBoard defaultBoard;
	private ProductBoard customBoard;
	private String ownerToken;
	private String otherToken;

	@BeforeEach
	void setUp() {
		reset(externalImageFetcher);
		if (objectStorageClient instanceof InMemoryObjectStorageClient memoryStorage) {
			memoryStorage.clear();
		}
		imageMirrorJobRepository.deleteAll();
		jdbc.update("DELETE FROM ziper.user_card_events");
		jdbc.update("DELETE FROM ziper.product_link_reports");
		jdbc.update("DELETE FROM ziper.product_board_card");
		jdbc.update("DELETE FROM ziper.product_card_tags");
		jdbc.update("DELETE FROM ziper.product_cards");
		jdbc.update("DELETE FROM ziper.product_links");
		jdbc.update("DELETE FROM ziper.tags WHERE is_default = FALSE");
		jdbc.update("DELETE FROM ziper.product_boards");
		jdbc.update("DELETE FROM ziper.users");
		fileAssetRepository.deleteAll();

		owner = userRepository
				.save(User.createEmailUser("detail-owner@ziper.test", "{bcrypt}hash", "상세유저", false, Instant.now()));
		otherUser = userRepository
				.save(User.createEmailUser("detail-other@ziper.test", "{bcrypt}hash", "타인유저", false, Instant.now()));

		defaultBoard = productBoardRepository.save(ProductBoard.createDefault(owner.getId()));
		customBoard = productBoardRepository.save(ProductBoard.createCustom(owner.getId(), "가을 코디"));
		Instant earlier = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
		Instant later = Instant.now().minus(Duration.ofHours(1)).truncatedTo(ChronoUnit.SECONDS);
		jdbc.update("UPDATE ziper.product_boards SET created_at = ? WHERE id = ?", Timestamp.from(earlier),
				defaultBoard.getId());
		jdbc.update("UPDATE ziper.product_boards SET created_at = ? WHERE id = ?", Timestamp.from(later),
				customBoard.getId());

		ownerToken = jwtTokenProvider.issueAccessToken(owner.getId());
		otherToken = jwtTokenProvider.issueAccessToken(otherUser.getId());
	}

	@Test
	void 재발견2단계카드가있을때_상세조회를호출하면_전체필드반환과14일리셋및VIEW이벤트가저장된다() throws Exception {
		ImageUploadResponse uploaded = fileImageService.uploadDirectImage(createJpeg(400, 400), "thumb.jpg",
				"image/jpeg", 4096);
		String longTitle = "오버핏 코튼 스트라이프 셔츠 ".repeat(8).trim();
		String longMemo = "세일 기간에 사이즈 확인 후 꼭 구매할 예정인 아이템";

		long cardId = registerCard(ownerToken,
				Map.of("productUrl", "https://www.musinsa.com/products/1111111", "sharedUrl",
						"https://musinsa.onelink.me/detail1", "productTitle", longTitle, "price", 59000, "memo",
						longMemo, "imageFileId", uploaded.fileId(), "boardIds",
						List.of(defaultBoard.getId(), customBoard.getId()), "tags", List.of("데일리", "봄", "출근룩"),
						"metadataSource", "CLIENT"));

		Instant past = Instant.now().minus(Duration.ofDays(40)).truncatedTo(ChronoUnit.SECONDS);
		jdbc.update(
				"UPDATE ziper.product_cards SET rediscovery_stage = 2, last_interacted_at = ?, next_rediscovery_at = ? WHERE id = ?",
				Timestamp.from(past), Timestamp.from(past.plus(Duration.ofDays(90))), cardId);

		String body = mvc
				.perform(get("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.cardId").value(cardId))
				.andExpect(jsonPath("$.productUrl").value("https://www.musinsa.com/products/1111111"))
				.andExpect(jsonPath("$.sharedUrl").value("https://musinsa.onelink.me/detail1"))
				.andExpect(jsonPath("$.shop").value("무신사")).andExpect(jsonPath("$.productTitle").value(longTitle))
				.andExpect(jsonPath("$.price").value(59000)).andExpect(jsonPath("$.memo").value(longMemo))
				.andExpect(jsonPath("$.imageFileId").value(uploaded.fileId()))
				.andExpect(jsonPath("$.imageFileUrl").isNotEmpty()).andExpect(jsonPath("$.imageStatus").value("READY"))
				.andExpect(jsonPath("$.metadataSource").value("CLIENT"))
				.andExpect(jsonPath("$.status").value("PLANNED")).andExpect(jsonPath("$.link.status").value("UNKNOWN"))
				.andExpect(jsonPath("$.rediscoveryStage").value(0)).andExpect(jsonPath("$.tags.length()").value(3))
				.andExpect(jsonPath("$.tags[0].title").value("봄"))
				.andExpect(jsonPath("$.tags[0].isDefault").value(true))
				.andExpect(jsonPath("$.tags[1].title").value("데일리"))
				.andExpect(jsonPath("$.tags[1].isDefault").value(false))
				.andExpect(jsonPath("$.tags[2].title").value("출근룩"))
				.andExpect(jsonPath("$.tags[2].isDefault").value(false))
				.andExpect(jsonPath("$.boards.length()").value(2))
				.andExpect(jsonPath("$.boards[0].boardId").value(customBoard.getId()))
				.andExpect(jsonPath("$.boards[0].name").value("가을 코디"))
				.andExpect(jsonPath("$.boards[0].modifiable").value(true))
				.andExpect(jsonPath("$.boards[1].boardId").value(defaultBoard.getId()))
				.andExpect(jsonPath("$.boards[1].name").value("기본 보드"))
				.andExpect(jsonPath("$.boards[1].modifiable").value(false))
				.andExpect(jsonPath("$.createdAt").isNotEmpty()).andExpect(jsonPath("$.updatedAt").isNotEmpty())
				.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

		JsonNode json = objectMapper.readTree(body);
		Instant lastInteractedAt = Instant.parse(json.path("lastInteractedAt").asText());
		Instant nextRediscoveryAt = Instant.parse(json.path("nextRediscoveryAt").asText());
		assertThat(lastInteractedAt).isAfter(past);
		assertThat(Duration.between(lastInteractedAt, nextRediscoveryAt)).isEqualTo(Duration.ofDays(14));

		ProductCard reloaded = productCardRepository.findById(cardId).orElseThrow();
		assertThat(reloaded.getRediscoveryStage()).isZero();
		assertThat(Duration.between(reloaded.getLastInteractedAt(), reloaded.getNextRediscoveryAt()))
				.isEqualTo(Duration.ofDays(14));

		List<UserCardEvent> events = userCardEventRepository.findAllByCardIdOrderByViewedAtDescIdDesc(cardId);
		assertThat(events).singleElement().satisfies(event -> {
			assertThat(event.getUserId()).isEqualTo(owner.getId());
			assertThat(event.getCardId()).isEqualTo(cardId);
			assertThat(event.getEventType()).isEqualTo(UserCardEventType.VIEW_DETAIL);
		});
	}

	@Test
	void 링크가삭제상태인카드가있을때_상세조회와수정을호출하면_저장정보와링크상태를유지한채성공한다() throws Exception {
		long cardId = registerCard(ownerToken,
				Map.of("productUrl", "https://www.musinsa.com/products/2222222", "productTitle", "품절임박 니트", "price",
						45000, "fallbackIcon", "TOP", "boardIds", List.of(defaultBoard.getId()), "tags", List.of("가을"),
						"metadataSource", "MANUAL"));

		Instant checkedAt = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
		jdbc.update(
				"UPDATE ziper.product_cards SET link_status = 'REMOVED', link_checked_at = ?, link_check_source = 'CLIENT' WHERE id = ?",
				Timestamp.from(checkedAt), cardId);

		mvc.perform(get("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.productTitle").value("품절임박 니트"))
				.andExpect(jsonPath("$.link.status").value("REMOVED"))
				.andExpect(jsonPath("$.link.checkedAt").isNotEmpty())
				.andExpect(jsonPath("$.link.source").value("CLIENT"));

		Map<String, Object> updatePayload = new LinkedHashMap<>();
		updatePayload.put("productTitle", "삭제된 링크지만 메모 수정");
		updatePayload.put("price", 42000);
		updatePayload.put("memo", "대체 상품 찾기");
		updatePayload.put("fallbackIcon", "TOP");
		updatePayload.put("tags", List.of("가을"));
		updatePayload.put("boardIds", List.of(defaultBoard.getId()));

		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(updatePayload)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.productTitle").value("삭제된 링크지만 메모 수정"))
				.andExpect(jsonPath("$.link.status").value("REMOVED"))
				.andExpect(jsonPath("$.link.source").value("CLIENT"));
	}

	@Test
	void 재발견2단계카드가있을때_원본이동이벤트를호출하면_상태변경없이14일리셋과OPEN이벤트가저장된다() throws Exception {
		long cardId = registerCard(ownerToken,
				Map.of("productUrl", "https://www.musinsa.com/products/3333333", "productTitle", "원본 이동 테스트 자켓",
						"price", 129000, "fallbackIcon", "TOP", "boardIds", List.of(defaultBoard.getId()), "tags",
						List.of("겨울"), "metadataSource", "CLIENT"));

		Instant past = Instant.now().minus(Duration.ofDays(50)).truncatedTo(ChronoUnit.SECONDS);
		jdbc.update(
				"UPDATE ziper.product_cards SET rediscovery_stage = 2, last_interacted_at = ?, next_rediscovery_at = ? WHERE id = ?",
				Timestamp.from(past), Timestamp.from(past.plus(Duration.ofDays(90))), cardId);

		String body = mvc
				.perform(post("/api/v1/products/{cardId}/open-original", cardId).header(HttpHeaders.AUTHORIZATION,
						bearer(ownerToken)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.cardId").value(cardId))
				.andExpect(jsonPath("$.productUrl").value("https://www.musinsa.com/products/3333333"))
				.andExpect(jsonPath("$.rediscoveryStage").value(0)).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);

		JsonNode json = objectMapper.readTree(body);
		Instant lastInteractedAt = Instant.parse(json.path("lastInteractedAt").asText());
		Instant nextRediscoveryAt = Instant.parse(json.path("nextRediscoveryAt").asText());
		assertThat(Duration.between(lastInteractedAt, nextRediscoveryAt)).isEqualTo(Duration.ofDays(14));

		ProductCard reloaded = productCardRepository.findById(cardId).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(ProductCardStatus.PLANNED);
		assertThat(reloaded.getRediscoveryStage()).isZero();

		List<UserCardEvent> events = userCardEventRepository.findAllByCardIdOrderByViewedAtDescIdDesc(cardId);
		assertThat(events).singleElement().satisfies(event -> {
			assertThat(event.getUserId()).isEqualTo(owner.getId());
			assertThat(event.getEventType()).isEqualTo(UserCardEventType.OPEN_ORIGINAL_URL);
		});
	}

	@Test
	void 기존보드연결과외부이미지가있을때_상품정보를수정하면_유지보드생성시각보존과미러링재등록이수행된다() throws Exception {
		long cardId = registerCard(ownerToken,
				Map.of("productUrl", "https://www.musinsa.com/products/4444444", "productTitle", "기존 티셔츠", "price",
						29000, "imageUrl", "https://image.msscdn.net/images/goods/old.jpg", "boardIds",
						List.of(defaultBoard.getId(), customBoard.getId()), "tags", List.of("봄", "기존태그"),
						"metadataSource", "CLIENT"));

		Instant preservedCreatedAt = Instant.now().minus(Duration.ofDays(5)).truncatedTo(ChronoUnit.SECONDS);
		jdbc.update("UPDATE ziper.product_board_card SET created_at = ? WHERE board_id = ? AND card_id = ?",
				Timestamp.from(preservedCreatedAt), defaultBoard.getId(), cardId);
		long preservedLinkId = productBoardCardRepository.findAllByCardIdOrderByIdAsc(cardId).stream()
				.filter(link -> link.getBoardId().equals(defaultBoard.getId())).findFirst().orElseThrow().getId();

		ProductBoard thirdBoard = productBoardRepository.save(ProductBoard.createCustom(owner.getId(), "위시 보드"));

		Map<String, Object> updatePayload = new LinkedHashMap<>();
		updatePayload.put("productTitle", "<b>수정된</b>   스트라이프\t셔츠");
		updatePayload.put("shop", "임의조작쇼핑몰");
		updatePayload.put("price", 35000);
		updatePayload.put("memo", "  할인 쿠폰 적용  ");
		updatePayload.put("imageUrl", "https://image.msscdn.net/images/goods/new.jpg");
		updatePayload.put("imageFileId", null);
		updatePayload.put("fallbackIcon", null);
		updatePayload.put("tags", List.of("#새태그", "여름"));
		updatePayload.put("boardIds", List.of(defaultBoard.getId(), thirdBoard.getId(), defaultBoard.getId()));

		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(updatePayload)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.productTitle").value("수정된 스트라이프 셔츠"))
				.andExpect(jsonPath("$.shop").value("무신사")).andExpect(jsonPath("$.price").value(35000))
				.andExpect(jsonPath("$.memo").value("할인 쿠폰 적용"))
				.andExpect(jsonPath("$.imageUrl").value("https://image.msscdn.net/images/goods/new.jpg"))
				.andExpect(jsonPath("$.imageStatus").value("PENDING")).andExpect(jsonPath("$.tags.length()").value(2))
				.andExpect(jsonPath("$.tags[0].title").value("여름"))
				.andExpect(jsonPath("$.tags[0].isDefault").value(true))
				.andExpect(jsonPath("$.tags[1].title").value("새태그"))
				.andExpect(jsonPath("$.tags[1].isDefault").value(false))
				.andExpect(jsonPath("$.boards.length()").value(2))
				.andExpect(jsonPath("$.boards[0].boardId").value(thirdBoard.getId()))
				.andExpect(jsonPath("$.boards[1].boardId").value(defaultBoard.getId()));

		List<ProductBoardCard> boardCards = productBoardCardRepository.findAllByCardIdOrderByIdAsc(cardId);
		assertThat(boardCards).extracting(ProductBoardCard::getBoardId).containsExactlyInAnyOrder(defaultBoard.getId(),
				thirdBoard.getId());
		ProductBoardCard preservedLink = boardCards.stream()
				.filter(link -> link.getBoardId().equals(defaultBoard.getId())).findFirst().orElseThrow();
		assertThat(preservedLink.getId()).isEqualTo(preservedLinkId);
		assertThat(preservedLink.getCreatedAt().truncatedTo(ChronoUnit.SECONDS)).isEqualTo(preservedCreatedAt);

		List<ImageMirrorJob> jobs = imageMirrorJobRepository.findByCardIdOrderByIdDesc(cardId);
		assertThat(jobs).hasSize(2);
		assertThat(jobs.get(0).getSourceUrl()).isEqualTo("https://image.msscdn.net/images/goods/new.jpg");
		assertThat(jobs.get(0).getStatus()).isEqualTo(ImageMirrorJobStatus.PENDING);
	}

	@Test
	void 미지원쇼핑몰카드에서_이미지와쇼핑몰을수정하면_직접업로드파일과대체아이콘전환이반영된다() throws Exception {
		long cardId = registerCard(ownerToken,
				Map.of("productUrl", "https://shop.example.com/items/5555", "shop", "초기쇼핑몰", "productTitle", "미지원 상품",
						"price", 15000, "fallbackIcon", "TOP", "boardIds", List.of(defaultBoard.getId()), "tags",
						List.of("봄"), "metadataSource", "MANUAL"));

		ImageUploadResponse uploaded = fileImageService.uploadDirectImage(createJpeg(300, 300), "custom.jpg",
				"image/jpeg", 2048);

		// 1. Direct upload file + custom shop update -> READY
		Map<String, Object> toFilePayload = new LinkedHashMap<>();
		toFilePayload.put("productTitle", "직접 업로드 전환 상품");
		toFilePayload.put("shop", "커스텀 편집샵");
		toFilePayload.put("price", 18000);
		toFilePayload.put("imageFileId", uploaded.fileId());
		toFilePayload.put("tags", List.of("봄"));
		toFilePayload.put("boardIds", List.of(defaultBoard.getId()));

		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(toFilePayload)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.shop").value("커스텀 편집샵"))
				.andExpect(jsonPath("$.imageFileId").value(uploaded.fileId()))
				.andExpect(jsonPath("$.imageFileUrl").isNotEmpty()).andExpect(jsonPath("$.imageStatus").value("READY"));

		// 2. Switch to fallbackIcon only -> NONE
		Map<String, Object> toFallbackPayload = new LinkedHashMap<>();
		toFallbackPayload.put("productTitle", "대체 아이콘 전환 상품");
		toFallbackPayload.put("shop", "   ");
		toFallbackPayload.put("price", 18000);
		toFallbackPayload.put("fallbackIcon", "SKIRT");
		toFallbackPayload.put("tags", List.of("봄"));
		toFallbackPayload.put("boardIds", List.of(defaultBoard.getId()));

		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(toFallbackPayload)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.shop").doesNotExist())
				.andExpect(jsonPath("$.imageFileId").doesNotExist()).andExpect(jsonPath("$.imageUrl").doesNotExist())
				.andExpect(jsonPath("$.fallbackIcon").value("SKIRT"))
				.andExpect(jsonPath("$.imageStatus").value("NONE"));

		// 3. Switch from NONE to external imageUrl -> enqueue mirror job -> PENDING
		Map<String, Object> toExternalUrlPayload = new LinkedHashMap<>();
		toExternalUrlPayload.put("productTitle", "외부 이미지 전환 상품");
		toExternalUrlPayload.put("shop", "편집샵B");
		toExternalUrlPayload.put("price", 20000);
		toExternalUrlPayload.put("imageUrl", "https://cdn.example.com/item-5555.jpg");
		toExternalUrlPayload.put("tags", List.of("봄"));
		toExternalUrlPayload.put("boardIds", List.of(defaultBoard.getId()));

		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(toExternalUrlPayload)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.imageStatus").value("PENDING"));
		assertThat(imageMirrorJobRepository.findByCardIdOrderByIdDesc(cardId)).hasSize(1);

		// 4. Update title only keeping the exact same pending imageUrl -> no duplicate
		// mirror job
		toExternalUrlPayload.put("productTitle", "제목만 변경");
		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(toExternalUrlPayload)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.imageStatus").value("PENDING"));
		assertThat(imageMirrorJobRepository.findByCardIdOrderByIdDesc(cardId)).hasSize(1);

		ProductCard saved = productCardRepository.findById(cardId).orElseThrow();
		assertThat(saved.getImageStatus()).isEqualTo(ProductImageStatus.PENDING);
	}

	@Test
	void 잘못된수정요청이주어지면_상품수정을호출하면_적절한400과404에러를반환한다() throws Exception {
		long cardId = registerCard(ownerToken,
				Map.of("productUrl", "https://www.musinsa.com/products/6666666", "productTitle", "검증 테스트 상품", "price",
						10000, "fallbackIcon", "TOP", "boardIds", List.of(defaultBoard.getId()), "tags", List.of("봄"),
						"metadataSource", "MANUAL"));

		// Blank title -> 400 INVALID_PRODUCT_CARD_INPUT
		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(validUpdatePayload("<b></b>", 10000, null, null, null, "TOP",
						List.of("봄"), List.of(defaultBoard.getId())))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRODUCT_CARD_INPUT"));

		// Price out of range -> 400 INVALID_PRODUCT_CARD_INPUT
		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(validUpdatePayload("정상 상품", 1_000_000_001L, null, null, null,
						"TOP", List.of("봄"), List.of(defaultBoard.getId())))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRODUCT_CARD_INPUT"));

		// Memo too long -> 400 INVALID_PRODUCT_CARD_INPUT
		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(validUpdatePayload("정상 상품", 10000, "가".repeat(101), null, null,
						"TOP", List.of("봄"), List.of(defaultBoard.getId())))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRODUCT_CARD_INPUT"));

		// Disallowed image host on supported shop -> 400 INVALID_PRODUCT_IMAGE_URL
		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(validUpdatePayload("정상 상품", 10000, null,
						"https://evil.example.com/pic.jpg", null, null, List.of("봄"), List.of(defaultBoard.getId())))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRODUCT_IMAGE_URL"));

		// Invalid fallback icon -> 400 INVALID_FALLBACK_ICON
		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(validUpdatePayload("정상 상품", 10000, null, null, null, "SHOES",
						List.of("봄"), List.of(defaultBoard.getId())))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_FALLBACK_ICON"));

		// Missing all image sources -> 400 INVALID_PRODUCT_CARD_INPUT
		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(validUpdatePayload("정상 상품", 10000, null, null, null, null,
						List.of("봄"), List.of(defaultBoard.getId())))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PRODUCT_CARD_INPUT"));

		// Empty boardIds -> 400 INVALID_BOARD_SELECTION
		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(
						validUpdatePayload("정상 상품", 10000, null, null, null, "TOP", List.of("봄"), List.of()))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BOARD_SELECTION"));

		// Empty tags -> 400 INVALID_TAG_TITLE
		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(validUpdatePayload("정상 상품", 10000, null, null, null, "TOP",
						List.of(), List.of(defaultBoard.getId())))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TAG_TITLE"));

		// Missing imageFileId -> 404 FILE_NOT_FOUND
		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(validUpdatePayload("정상 상품", 10000, null, null, 999999L, null,
						List.of("봄"), List.of(defaultBoard.getId())))))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));

		// Other user's board -> 404 BOARD_NOT_FOUND
		ProductBoard otherBoard = productBoardRepository.save(ProductBoard.createDefault(otherUser.getId()));
		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(validUpdatePayload("정상 상품", 10000, null, null, null, "TOP",
						List.of("봄"), List.of(otherBoard.getId())))))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOARD_NOT_FOUND"));
	}

	@Test
	void 활성상품카드가있을때_전체삭제를호출하면_소프트삭제되고보드매핑은유지된다() throws Exception {
		long cardId = registerCard(ownerToken,
				Map.of("productUrl", "https://www.musinsa.com/products/7777777", "productTitle", "삭제 대상 카드", "price",
						33000, "fallbackIcon", "PANTS", "boardIds", List.of(defaultBoard.getId(), customBoard.getId()),
						"tags", List.of("봄"), "metadataSource", "CLIENT"));

		mvc.perform(delete("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
				.andExpect(status().isNoContent());

		ProductCard deletedCard = productCardRepository.findById(cardId).orElseThrow();
		assertThat(deletedCard.isDeleted()).isTrue();
		assertThat(deletedCard.getDeletedAt()).isNotNull();

		// CRITICAL INVARIANT: product_board_card rows must remain intact for trash
		// restore previousBoardIds
		List<ProductBoardCard> preservedMappings = productBoardCardRepository.findAllByCardIdOrderByIdAsc(cardId);
		assertThat(preservedMappings).extracting(ProductBoardCard::getBoardId)
				.containsExactlyInAnyOrder(defaultBoard.getId(), customBoard.getId());

		// Subsequent calls on soft-deleted card return 404 PRODUCT_CARD_NOT_FOUND
		mvc.perform(get("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));
		mvc.perform(post("/api/v1/products/{cardId}/open-original", cardId).header(HttpHeaders.AUTHORIZATION,
				bearer(ownerToken))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));
		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(validUpdatePayload("수정 불가", 10000, null, null, null, "TOP",
						List.of("봄"), List.of(defaultBoard.getId())))))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));
		mvc.perform(delete("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));
	}

	@Test
	void 삭제된보드가연결되어있을때_상세조회를호출하면_활성보드만정렬되어반환된다() throws Exception {
		ProductBoard deletedBoard = productBoardRepository.save(ProductBoard.createCustom(owner.getId(), "삭제될 보드"));
		long cardId = registerCard(ownerToken, Map.of("productUrl", "https://www.musinsa.com/products/8888888",
				"productTitle", "삭제 보드 필터 테스트", "price", 25000, "fallbackIcon", "ACCESSORY", "boardIds",
				List.of(defaultBoard.getId(), deletedBoard.getId()), "tags", List.of("봄"), "metadataSource", "CLIENT"));

		deletedBoard.softDelete(Instant.now());
		productBoardRepository.saveAndFlush(deletedBoard);

		mvc.perform(get("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.boards.length()").value(1))
				.andExpect(jsonPath("$.boards[0].boardId").value(defaultBoard.getId()));
	}

	@Test
	void 미인증또는타인카드요청이주어지면_상세API를호출하면_401과404를반환한다() throws Exception {
		long cardId = registerCard(ownerToken,
				Map.of("productUrl", "https://www.musinsa.com/products/9999999", "productTitle", "권한 검증 카드", "price",
						20000, "fallbackIcon", "BEAUTY", "boardIds", List.of(defaultBoard.getId()), "tags",
						List.of("봄"), "metadataSource", "MANUAL"));

		String updateBody = objectMapper.writeValueAsString(validUpdatePayload("타인 수정 시도", 10000, null, null, null,
				"TOP", List.of("봄"), List.of(defaultBoard.getId())));

		// Unauthenticated -> 401
		mvc.perform(get("/api/v1/products/{cardId}", cardId)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/products/{cardId}/open-original", cardId)).andExpect(status().isUnauthorized());
		mvc.perform(
				patch("/api/v1/products/{cardId}", cardId).contentType(MediaType.APPLICATION_JSON).content(updateBody))
				.andExpect(status().isUnauthorized());
		mvc.perform(delete("/api/v1/products/{cardId}", cardId)).andExpect(status().isUnauthorized());

		// Other user -> 404 PRODUCT_CARD_NOT_FOUND
		mvc.perform(get("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));
		mvc.perform(post("/api/v1/products/{cardId}/open-original", cardId).header(HttpHeaders.AUTHORIZATION,
				bearer(otherToken))).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));
		mvc.perform(patch("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(otherToken))
				.contentType(MediaType.APPLICATION_JSON).content(updateBody)).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));
		mvc.perform(delete("/api/v1/products/{cardId}", cardId).header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PRODUCT_CARD_NOT_FOUND"));
	}

	private long registerCard(String token, Map<String, Object> payload) throws Exception {
		String body = mvc
				.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(token))
						.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(payload)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		return objectMapper.readTree(body).path("cardId").asLong();
	}

	private static Map<String, Object> validUpdatePayload(String title, Number price, String memo, String imageUrl,
			Long imageFileId, String fallbackIcon, List<String> tags, List<Long> boardIds) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("productTitle", title);
		payload.put("price", price);
		payload.put("memo", memo);
		payload.put("imageUrl", imageUrl);
		payload.put("imageFileId", imageFileId);
		payload.put("fallbackIcon", fallbackIcon);
		payload.put("tags", tags);
		payload.put("boardIds", boardIds);
		return payload;
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private static byte[] createJpeg(int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		graphics.setColor(Color.DARK_GRAY);
		graphics.fillRect(0, 0, width, height);
		graphics.dispose();
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", output);
		return output.toByteArray();
	}
}
