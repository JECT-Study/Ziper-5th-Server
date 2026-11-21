package com.ject.ziper.domain.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ject.ziper.domain.file.client.ExternalImageFetcher;
import com.ject.ziper.domain.file.client.ExternalImageFetcher.FetchResult;
import com.ject.ziper.domain.file.entity.FileAsset;
import com.ject.ziper.domain.file.entity.ImageMirrorJob;
import com.ject.ziper.domain.file.entity.ImageMirrorJobStatus;
import com.ject.ziper.domain.file.repository.FileAssetRepository;
import com.ject.ziper.domain.file.repository.ImageMirrorJobRepository;
import com.ject.ziper.domain.file.service.ImageMirrorService;
import com.ject.ziper.domain.file.worker.ImageMirrorWorker;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.storage.InMemoryObjectStorageClient;
import com.ject.ziper.global.storage.ObjectStorageClient;
import com.ject.ziper.global.storage.StorageProperties;
import com.ject.ziper.global.storage.WebpThumbnailProcessor;

@SpringBootTest
@ActiveProfiles("test")
class ImageMirrorIntegrationTest extends PostgresTestSupport {
	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ImageMirrorService imageMirrorService;

	@Autowired
	private ImageMirrorWorker imageMirrorWorker;

	@Autowired
	private ImageMirrorJobRepository jobRepository;

	@Autowired
	private FileAssetRepository fileAssetRepository;

	@Autowired
	private ObjectStorageClient objectStorageClient;

	@MockitoBean
	private ExternalImageFetcher externalImageFetcher;

	private InMemoryObjectStorageClient inMemoryStorage;

	@BeforeEach
	void setUp() {
		reset(externalImageFetcher);
		inMemoryStorage = (InMemoryObjectStorageClient) objectStorageClient;
		inMemoryStorage.clear();
		jobRepository.deleteAll();
		jdbc.update("DELETE FROM ziper.product_cards");
		jdbc.update("DELETE FROM ziper.users");
		fileAssetRepository.deleteAll();
	}

	@Test
	void 만료형URL과일반URL미러링작업_등록후워커가클레임처리하면_우선순위반영및WebP파일과카드를연결한다() throws Exception {
		Long userId = insertUser("mirror-ok@ziper.test", "미러유저1");
		Long normalCardId = insertCard(userId, "https://www.musinsa.com/products/1",
				"https://image.msscdn.net/images/goods/normal.jpg");
		Long expiringCardId = insertCard(userId, "https://www.instagram.com/p/abc",
				"https://scontent.cdninstagram.com/v/img.jpg?oe=67A12345");

		assertThat(imageMirrorService.enqueueMirrorJob(normalCardId, userId,
				"https://image.msscdn.net/images/goods/normal.jpg", "무신사")).isTrue();
		assertThat(imageMirrorService.enqueueMirrorJob(expiringCardId, userId,
				"https://scontent.cdninstagram.com/v/img.jpg?oe=67A12345", null)).isTrue();

		ImageMirrorJob normalJob = jobRepository.findByCardIdOrderByIdDesc(normalCardId).getFirst();
		ImageMirrorJob expiringJob = jobRepository.findByCardIdOrderByIdDesc(expiringCardId).getFirst();
		assertThat(normalJob.getPriority()).isEqualTo((short) 0);
		assertThat(expiringJob.getPriority()).isEqualTo((short) 10);

		byte[] sampleJpeg = createJpeg(900, 900);
		when(externalImageFetcher.fetch(any(), any(), any())).thenReturn(FetchResult.ok(sampleJpeg, "image/jpeg"));

		int processed = imageMirrorWorker.processPendingJobs();
		assertThat(processed).isEqualTo(2);

		var inOrder = inOrder(externalImageFetcher);
		inOrder.verify(externalImageFetcher).fetch(eq("https://scontent.cdninstagram.com/v/img.jpg?oe=67A12345"), any(),
				eq(null));
		inOrder.verify(externalImageFetcher).fetch(eq("https://image.msscdn.net/images/goods/normal.jpg"), any(),
				eq("무신사"));

		ImageMirrorJob finishedNormal = jobRepository.findById(normalJob.getId()).orElseThrow();
		ImageMirrorJob finishedExpiring = jobRepository.findById(expiringJob.getId()).orElseThrow();
		assertThat(finishedNormal.getStatus()).isEqualTo(ImageMirrorJobStatus.READY);
		assertThat(finishedNormal.getAttemptCount()).isEqualTo(1);
		assertThat(finishedExpiring.getStatus()).isEqualTo(ImageMirrorJobStatus.READY);
		assertThat(finishedExpiring.getLockedAt()).isNotNull();

		Long linkedFileId = jdbc.queryForObject("SELECT image_file_id FROM ziper.product_cards WHERE id = ?",
				Long.class, normalCardId);
		assertThat(linkedFileId).isNotNull();

		FileAsset asset = fileAssetRepository.findById(linkedFileId).orElseThrow();
		assertThat(asset.getContentType()).isEqualTo("image/webp");
		var stored = inMemoryStorage.getObject(asset.getStorageKey()).orElseThrow();
		assertThat(WebpThumbnailProcessor.isWebpMagic(stored.content())).isTrue();
	}

	@Test
	void 외부이미지다운로드실패상황_워커가미러링작업을처리하면_FAILED기록후원본imageUrl을보존한다() {
		Long userId = insertUser("mirror-fail@ziper.test", "미러유저2");
		String originalUrl = "https://image.msscdn.net/images/goods/blocked.jpg";
		Long cardId = insertCard(userId, "https://www.musinsa.com/products/2", originalUrl);

		assertThat(imageMirrorService.enqueueMirrorJob(cardId, userId, originalUrl, "무신사")).isTrue();
		when(externalImageFetcher.fetch(eq(originalUrl), any(), eq("무신사")))
				.thenReturn(FetchResult.failure(403, "HTTP_403"));

		assertThat(imageMirrorWorker.processPendingJobs()).isEqualTo(1);

		ImageMirrorJob failedJob = jobRepository.findByCardIdOrderByIdDesc(cardId).getFirst();
		assertThat(failedJob.getStatus()).isEqualTo(ImageMirrorJobStatus.FAILED);
		assertThat(failedJob.getLastError()).isEqualTo("HTTP_403");

		Long imageFileId = jdbc.queryForObject("SELECT image_file_id FROM ziper.product_cards WHERE id = ?", Long.class,
				cardId);
		String preservedUrl = jdbc.queryForObject("SELECT image_url FROM ziper.product_cards WHERE id = ?",
				String.class, cardId);
		assertThat(imageFileId).isNull();
		assertThat(preservedUrl).isEqualTo(originalUrl);
	}

	@Test
	void 전역킬스위치또는호스트비활성화상태_미러링등록을요청하면_작업을생성하지않고건너뛴다() {
		Long userId = insertUser("mirror-off@ziper.test", "미러유저3");
		Long cardId = insertCard(userId, "https://www.musinsa.com/products/3",
				"https://image.msscdn.net/images/goods/off.jpg");

		StorageProperties killSwitchOff = new StorageProperties("memory", "", "", "product-images", 900L,
				StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES, false, List.of(), false, 5000L, 5, 5000, 10000, 2, 0L, 3,
				300L, 30L);
		ImageMirrorService disabledService = new ImageMirrorService(jobRepository, killSwitchOff, imageMirrorWorker);
		assertThat(disabledService.enqueueMirrorJob(cardId, userId, "https://image.msscdn.net/images/goods/off.jpg",
				"무신사")).isFalse();

		StorageProperties hostDisabled = new StorageProperties("memory", "", "", "product-images", 900L,
				StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES, true, List.of("image.msscdn.net"), false, 5000L, 5, 5000,
				10000, 2, 0L, 3, 300L, 30L);
		ImageMirrorService hostDisabledService = new ImageMirrorService(jobRepository, hostDisabled, imageMirrorWorker);
		assertThat(hostDisabledService.enqueueMirrorJob(cardId, userId, "https://image.msscdn.net/images/goods/off.jpg",
				"무신사")).isFalse();

		assertThat(jobRepository.count()).isZero();
	}

	private Long insertUser(String email, String nickname) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.users(email, nickname, status) VALUES (?, ?, 'ACTIVE') RETURNING id", Long.class,
				email, nickname);
	}

	private Long insertCard(Long userId, String productUrl, String imageUrl) {
		return jdbc
				.queryForObject(
						"INSERT INTO ziper.product_cards(user_id, product_url, product_title, price, image_url) "
								+ "VALUES (?, ?, '테스트 상품', 29000, ?) RETURNING id",
						Long.class, userId, productUrl, imageUrl);
	}

	private static byte[] createJpeg(int width, int height) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(Color.BLUE);
			graphics.fillRect(0, 0, width, height);
		} finally {
			graphics.dispose();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}
}
