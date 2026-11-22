package com.ject.ziper.domain.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ject.ziper.domain.file.client.ExternalImageFetcher;
import com.ject.ziper.domain.file.client.ExternalImageFetcher.FetchResult;
import com.ject.ziper.domain.file.entity.FileAsset;
import com.ject.ziper.domain.file.entity.ImageMirrorJob;
import com.ject.ziper.domain.file.entity.ImageMirrorJobStatus;
import com.ject.ziper.domain.file.repository.FileAssetRepository;
import com.ject.ziper.domain.file.repository.ImageMirrorJobRepository;
import com.ject.ziper.domain.file.repository.ProductCardImageUpdater;
import com.ject.ziper.domain.file.service.FileImageService;
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

	@Autowired
	private FileImageService fileImageService;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ProductCardImageUpdater productCardImageUpdater;

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
	void 백업에서이미지미러링작업을저장한뒤_홈워커가폴링하면_같은작업이완료되고카드에파일이연결된다() throws Exception {
		Long userId = insertUser("mirror-handoff@ziper.test", "미러복구");
		String imageUrl = "https://image.msscdn.net/images/goods/handoff.jpg";
		Long cardId = insertCard(userId, "https://www.musinsa.com/products/90", imageUrl);

		StorageProperties backup = bindOriginWorker(false);
		StorageProperties home = bindOriginWorker(true);
		assertThat(backup.workerEnabled()).isFalse();
		assertThat(home.workerEnabled()).isTrue();
		ExecutorService executor = mock(ExecutorService.class);
		ImageMirrorWorker backupWorker = new ImageMirrorWorker(jobRepository, externalImageFetcher, fileImageService,
				productCardImageUpdater, backup, transactionManager, executor);
		ImageMirrorService backupService = new ImageMirrorService(jobRepository, backup, backupWorker);
		new TransactionTemplate(transactionManager).executeWithoutResult(
				status -> assertThat(backupService.enqueueMirrorJob(cardId, userId, imageUrl, "무신사")).isTrue());
		backupWorker.pollPendingJobs();

		ImageMirrorJob queued = jobRepository.findByCardIdOrderByIdDesc(cardId).getFirst();
		assertThat(queued.getStatus()).isEqualTo(ImageMirrorJobStatus.PENDING);
		assertThat(queued.getAttemptCount()).isZero();
		verifyNoInteractions(executor, externalImageFetcher);

		when(externalImageFetcher.fetch(eq(imageUrl), any(), eq("무신사")))
				.thenReturn(FetchResult.ok(createJpeg(300, 400), "image/jpeg"));
		ImageMirrorWorker homeWorker = new ImageMirrorWorker(jobRepository, externalImageFetcher, fileImageService,
				productCardImageUpdater, home, transactionManager, executor);
		homeWorker.pollPendingJobs();

		assertThat(jobRepository.findById(queued.getId()).orElseThrow().getStatus())
				.isEqualTo(ImageMirrorJobStatus.READY);
		assertThat(
				jdbc.queryForObject("SELECT image_file_id FROM ziper.product_cards WHERE id = ?", Long.class, cardId))
				.isNotNull();
	}

	private static StorageProperties bindOriginWorker(boolean enabled) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("sharedRuntimeProperties",
				Map.of("app.storage.worker-enabled", !enabled, "app.storage.provider", "memory")));
		environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("origin-systemEnvironment",
				Map.of("APP_STORAGE_WORKERENABLED", Boolean.toString(enabled))));
		return Binder.get(environment).bind("app.storage", Bindable.of(StorageProperties.class)).get();
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
				300L, 30L, null, null, null, null, null, null, null);
		ImageMirrorService disabledService = new ImageMirrorService(jobRepository, killSwitchOff, imageMirrorWorker);
		assertThat(disabledService.enqueueMirrorJob(cardId, userId, "https://image.msscdn.net/images/goods/off.jpg",
				"무신사")).isFalse();

		StorageProperties hostDisabled = new StorageProperties("memory", "", "", "product-images", 900L,
				StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES, true, List.of("image.msscdn.net"), false, 5000L, 5, 5000,
				10000, 2, 0L, 3, 300L, 30L, null, null, null, null, null, null, null);
		ImageMirrorService hostDisabledService = new ImageMirrorService(jobRepository, hostDisabled, imageMirrorWorker);
		assertThat(hostDisabledService.enqueueMirrorJob(cardId, userId, "https://image.msscdn.net/images/goods/off.jpg",
				"무신사")).isFalse();

		assertThat(jobRepository.count()).isZero();
	}

	@Test
	void 워커가PROCESSING으로클레임후비정상종료된작업_1시간뒤폴링하면_재클레임해처리를완료한다() throws Exception {
		Long userId = insertUser("recover@ziper.test", "복구유저");
		String url = "https://img.example/recover.jpg";
		Long cardId = insertCard(userId, "https://shop.example/recover", url);
		Long jobId = insertJob(cardId, userId, url, "PROCESSING", 1, "now() - interval '1 hour'",
				"now() - interval '1 hour'");
		when(externalImageFetcher.fetch(any(), any(), any()))
				.thenReturn(FetchResult.ok(createJpeg(600, 600), "image/jpeg"));

		assertThat(imageMirrorWorker.processPendingJobs()).isEqualTo(1);

		ImageMirrorJob job = jobRepository.findById(jobId).orElseThrow();
		assertThat(job.getStatus()).isEqualTo(ImageMirrorJobStatus.READY);
		assertThat(job.getAttemptCount()).isEqualTo(2);
		Long fileId = jdbc.queryForObject("SELECT image_file_id FROM ziper.product_cards WHERE id = ?", Long.class,
				cardId);
		assertThat(fileId).isNotNull();
		FileAsset asset = fileAssetRepository.findById(fileId).orElseThrow();
		assertThat(asset.getStorageKey()).isEqualTo("thumbnails/mirror/" + jobId + ".webp");
		assertThat(asset.getOwnerUserId()).isEqualTo(userId);
	}

	@Test
	void 리스가아직유효한PROCESSING작업_폴링하면_가로채지않는다() {
		Long userId = insertUser("lease@ziper.test", "리스유저");
		String url = "https://img.example/lease.jpg";
		Long cardId = insertCard(userId, "https://shop.example/lease", url);
		insertJob(cardId, userId, url, "PROCESSING", 1, "now() - interval '1 minute'", "now() - interval '1 minute'");

		assertThat(imageMirrorWorker.processPendingJobs()).isZero();
		verifyNoInteractions(externalImageFetcher);
	}

	@Test
	void 이전시도가이미썸네일을저장한만료작업_재처리하면_다시다운로드하지않고저장결과로완료한다() throws Exception {
		Long userId = insertUser("idem@ziper.test", "멱등유저");
		String url = "https://img.example/idem.jpg";
		Long cardId = insertCard(userId, "https://shop.example/idem", url);
		Long jobId = insertJob(cardId, userId, url, "PROCESSING", 1, "now() - interval '1 hour'",
				"now() - interval '1 hour'");
		FileAsset stored = fileImageService.storeMirroredThumbnail(jobId, userId, createJpeg(500, 500), "image/jpeg",
				"idem.jpg");

		assertThat(imageMirrorWorker.processPendingJobs()).isEqualTo(1);

		verifyNoInteractions(externalImageFetcher);
		assertThat(jobRepository.findById(jobId).orElseThrow().getStatus()).isEqualTo(ImageMirrorJobStatus.READY);
		assertThat(
				jdbc.queryForObject("SELECT image_file_id FROM ziper.product_cards WHERE id = ?", Long.class, cardId))
				.isEqualTo(stored.getId());
		assertThat(fileAssetRepository.count()).isEqualTo(1L);
		assertThat(inMemoryStorage.size()).isEqualTo(1);
	}

	@Test
	void 시도횟수를소진한만료작업_폴링하면_다운로드없이FAILED로종결한다() {
		Long userId = insertUser("exhaust@ziper.test", "소진유저");
		String url = "https://img.example/exhaust.jpg";
		Long cardId = insertCard(userId, "https://shop.example/exhaust", url);
		Long jobId = insertJob(cardId, userId, url, "PROCESSING", 5, "now() - interval '1 hour'",
				"now() - interval '1 hour'");

		assertThat(imageMirrorWorker.processPendingJobs()).isEqualTo(1);

		verifyNoInteractions(externalImageFetcher);
		ImageMirrorJob job = jobRepository.findById(jobId).orElseThrow();
		assertThat(job.getStatus()).isEqualTo(ImageMirrorJobStatus.FAILED);
		assertThat(job.getLastError()).isEqualTo("MAX_ATTEMPTS_EXCEEDED");
	}

	@Test
	void 호스트서킷이열려다운로드가거부된작업_워커가처리하면_백오프후PENDING으로복귀하고기한전에는다시집지않는다() {
		Long userId = insertUser("circuit@ziper.test", "서킷유저");
		String url = "https://img.example/circuit.jpg";
		Long cardId = insertCard(userId, "https://shop.example/circuit", url);
		imageMirrorService.enqueueMirrorJob(cardId, userId, url, null);
		when(externalImageFetcher.fetch(any(), any(), any()))
				.thenReturn(FetchResult.failure(null, "HOST_CIRCUIT_OPEN"));

		assertThat(imageMirrorWorker.processPendingJobs()).isEqualTo(1);

		ImageMirrorJob job = jobRepository.findByCardIdOrderByIdDesc(cardId).getFirst();
		assertThat(job.getStatus()).isEqualTo(ImageMirrorJobStatus.PENDING);
		assertThat(job.getLastError()).isEqualTo("HOST_CIRCUIT_OPEN");
		assertThat(job.getAttemptCount()).isEqualTo(1);
		assertThat(job.getLockedAt()).isNull();
		assertThat(job.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(200));

		assertThat(imageMirrorWorker.processPendingJobs()).isZero();

		jdbc.update("UPDATE ziper.image_mirror_jobs SET next_attempt_at = now() - interval '1 second' WHERE id = ?",
				job.getId());
		when(externalImageFetcher.fetch(any(), any(), any())).thenReturn(FetchResult.ok(sampleJpeg(), "image/jpeg"));
		assertThat(imageMirrorWorker.processPendingJobs()).isEqualTo(1);
		assertThat(jobRepository.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(ImageMirrorJobStatus.READY);
	}

	@Test
	void 일시적오류가최대시도횟수를넘으면_워커가처리할때_FAILED로종결한다() {
		Long userId = insertUser("retry@ziper.test", "재시도유저");
		String url = "https://img.example/retry.jpg";
		Long cardId = insertCard(userId, "https://shop.example/retry", url);
		Long jobId = insertJob(cardId, userId, url, "PENDING", 4, "NULL", "now() - interval '1 second'");
		when(externalImageFetcher.fetch(any(), any(), any())).thenReturn(FetchResult.failure(503, "HTTP_503"));

		assertThat(imageMirrorWorker.processPendingJobs()).isEqualTo(1);

		ImageMirrorJob job = jobRepository.findById(jobId).orElseThrow();
		assertThat(job.getAttemptCount()).isEqualTo(5);
		assertThat(job.getStatus()).isEqualTo(ImageMirrorJobStatus.FAILED);
		assertThat(job.getLastError()).isEqualTo("HTTP_503");
	}

	@Test
	void 영구오류_워커가처리하면_재시도없이즉시FAILED로종결한다() {
		Long userId = insertUser("perm@ziper.test", "영구유저");
		String url = "https://img.example/perm.jpg";
		Long cardId = insertCard(userId, "https://shop.example/perm", url);
		imageMirrorService.enqueueMirrorJob(cardId, userId, url, null);
		when(externalImageFetcher.fetch(any(), any(), any())).thenReturn(FetchResult.failure(null, "IMAGE_TOO_LARGE"));

		imageMirrorWorker.processPendingJobs();

		ImageMirrorJob job = jobRepository.findByCardIdOrderByIdDesc(cardId).getFirst();
		assertThat(job.getStatus()).isEqualTo(ImageMirrorJobStatus.FAILED);
		assertThat(job.getAttemptCount()).isEqualTo(1);
	}

	@Test
	void 작업이후카드의imageUrl이바뀐경우_미러링이완료되면_카드를갱신하지않고작업을취소로닫는다() throws Exception {
		Long userId = insertUser("changed@ziper.test", "변경유저");
		String url = "https://img.example/old.jpg";
		Long cardId = insertCard(userId, "https://shop.example/changed", url);
		imageMirrorService.enqueueMirrorJob(cardId, userId, url, null);
		when(externalImageFetcher.fetch(any(), any(), any())).thenAnswer(invocation -> {
			jdbc.update("UPDATE ziper.product_cards SET image_url = 'https://img.example/new.jpg' WHERE id = ?",
					cardId);
			return FetchResult.ok(createJpeg(500, 500), "image/jpeg");
		});

		imageMirrorWorker.processPendingJobs();

		ImageMirrorJob job = jobRepository.findByCardIdOrderByIdDesc(cardId).getFirst();
		assertThat(job.getStatus()).isEqualTo(ImageMirrorJobStatus.CANCELED);
		assertThat(job.getLastError()).isEqualTo("CARD_IMAGE_CHANGED");
		assertThat(
				jdbc.queryForObject("SELECT image_file_id FROM ziper.product_cards WHERE id = ?", Long.class, cardId))
				.isNull();
	}

	@Test
	void 작업처리중카드가삭제된경우_미러링이완료되면_카드를갱신하지않는다() throws Exception {
		Long userId = insertUser("deleted@ziper.test", "삭제유저");
		String url = "https://img.example/del.jpg";
		Long cardId = insertCard(userId, "https://shop.example/deleted", url);
		imageMirrorService.enqueueMirrorJob(cardId, userId, url, null);
		when(externalImageFetcher.fetch(any(), any(), any())).thenAnswer(invocation -> {
			jdbc.update("UPDATE ziper.product_cards SET is_deleted = TRUE, deleted_at = now() WHERE id = ?", cardId);
			return FetchResult.ok(createJpeg(500, 500), "image/jpeg");
		});

		imageMirrorWorker.processPendingJobs();

		assertThat(jobRepository.findByCardIdOrderByIdDesc(cardId).getFirst().getStatus())
				.isEqualTo(ImageMirrorJobStatus.CANCELED);
		assertThat(
				jdbc.queryForObject("SELECT image_file_id FROM ziper.product_cards WHERE id = ?", Long.class, cardId))
				.isNull();
	}

	@Test
	void 카드의대기중과처리중작업_cancelPendingMirrorJobs를호출하면_취소되고완료된작업은유지된다() throws Exception {
		Long userId = insertUser("cancel@ziper.test", "취소유저");
		String url = "https://img.example/cancel.jpg";
		Long cardId = insertCard(userId, "https://shop.example/cancel", url);
		Long pending = insertJob(cardId, userId, url, "PENDING", 0, "NULL", "now()");
		Long processing = insertJob(cardId, userId, url, "PROCESSING", 1, "now()", "now()");
		Long ready = insertJob(cardId, userId, url, "READY", 1, "NULL", "now()");

		assertThat(imageMirrorService.cancelPendingMirrorJobs(cardId)).isEqualTo(2);

		assertThat(jobRepository.findById(pending).orElseThrow().getStatus()).isEqualTo(ImageMirrorJobStatus.CANCELED);
		assertThat(jobRepository.findById(processing).orElseThrow().getStatus())
				.isEqualTo(ImageMirrorJobStatus.CANCELED);
		assertThat(jobRepository.findById(ready).orElseThrow().getStatus()).isEqualTo(ImageMirrorJobStatus.READY);
		assertThat(imageMirrorWorker.processPendingJobs()).isZero();
		verifyNoInteractions(externalImageFetcher);
	}

	@Test
	void 처리중에작업이취소된경우_미러링이완료되면_카드를갱신하지않는다() throws Exception {
		Long userId = insertUser("cancelrace@ziper.test", "경합유저");
		String url = "https://img.example/race.jpg";
		Long cardId = insertCard(userId, "https://shop.example/race", url);
		imageMirrorService.enqueueMirrorJob(cardId, userId, url, null);
		when(externalImageFetcher.fetch(any(), any(), any())).thenAnswer(invocation -> {
			imageMirrorService.cancelPendingMirrorJobs(cardId);
			return FetchResult.ok(createJpeg(500, 500), "image/jpeg");
		});

		imageMirrorWorker.processPendingJobs();

		assertThat(jobRepository.findByCardIdOrderByIdDesc(cardId).getFirst().getStatus())
				.isEqualTo(ImageMirrorJobStatus.CANCELED);
		assertThat(
				jdbc.queryForObject("SELECT image_file_id FROM ziper.product_cards WHERE id = ?", Long.class, cardId))
				.isNull();
	}

	private byte[] sampleJpeg() {
		try {
			return createJpeg(500, 500);
		} catch (IOException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private Long insertJob(Long cardId, Long userId, String url, String status, int attempts, String lockedAtSql,
			String nextAttemptSql) {
		return jdbc.queryForObject("INSERT INTO ziper.image_mirror_jobs(card_id, user_id, source_url, source_url_hash, "
				+ "host, status, attempt_count, locked_at, next_attempt_at) VALUES (?, ?, ?, ?, 'img.example', ?, ?, "
				+ lockedAtSql + ", " + nextAttemptSql + ") RETURNING id", Long.class, cardId, userId, url,
				ExternalImageFetcher.sha256Hex(url + status + System.nanoTime()), status, attempts);
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
