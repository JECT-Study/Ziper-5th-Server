package com.ject.ziper.domain.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ject.ziper.domain.file.entity.FileAsset;
import com.ject.ziper.domain.file.repository.FileAssetRepository;
import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.global.error.ApiExceptionHandler;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.error.ProblemFactory;
import com.ject.ziper.global.persistence.PostgresTestSupport;
import com.ject.ziper.global.security.JwtTokenProvider;
import com.ject.ziper.global.storage.InMemoryObjectStorageClient;
import com.ject.ziper.global.storage.ObjectStorageClient;
import com.ject.ziper.global.storage.WebpThumbnailProcessor;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FileUploadIntegrationTest extends PostgresTestSupport {
	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private FileAssetRepository fileAssetRepository;

	@Autowired
	private FileImageService fileImageService;

	@Autowired
	private ObjectStorageClient objectStorageClient;

	private InMemoryObjectStorageClient inMemoryStorage;

	@BeforeEach
	void setUp() {
		inMemoryStorage = (InMemoryObjectStorageClient) objectStorageClient;
		inMemoryStorage.clear();
		fileAssetRepository.deleteAll();
		jdbc.update("DELETE FROM ziper.users");
	}

	private Long insertUser(String email, String nickname) {
		return jdbc.queryForObject(
				"INSERT INTO ziper.users(email, nickname, status) VALUES (?, ?, 'ACTIVE') RETURNING id", Long.class,
				email, nickname);
	}

	@Test
	void 인증된사용자와유효한JPEG이미지_직접업로드후삭제하면_480x640WebP저장서명URL발급및완전삭제를수행한다() throws Exception {
		Long uploaderId = insertUser("upload1@ziper.test", "업로더1");
		String accessToken = jwtTokenProvider.issueAccessToken(uploaderId);
		byte[] jpegBytes = createImageBytes(1000, 800, "jpg");
		MockMultipartFile multipartFile = new MockMultipartFile("file", "card-photo.jpg", "image/jpeg", jpegBytes);

		String body = mvc
				.perform(multipart("/api/v1/files/images").file(multipartFile).header(HttpHeaders.AUTHORIZATION,
						"Bearer " + accessToken))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.fileId").isNumber())
				.andExpect(jsonPath("$.contentType").value("image/webp")).andExpect(jsonPath("$.fileSize").isNumber())
				.andExpect(jsonPath("$.url").isNotEmpty()).andReturn().getResponse()
				.getContentAsString(StandardCharsets.UTF_8);

		JsonNode json = objectMapper.readTree(body);
		Long fileId = json.get("fileId").asLong();
		FileAsset asset = fileAssetRepository.findById(fileId).orElseThrow();
		assertThat(asset.getContentType()).isEqualTo("image/webp");
		assertThat(asset.getStorageKey()).startsWith("thumbnails/").endsWith(".webp");
		assertThat(asset.getOriginalFilename()).isEqualTo("card-photo.jpg");
		assertThat(asset.getOwnerUserId()).isEqualTo(uploaderId);
		Long otherUserId = insertUser("upload-other@ziper.test", "다른사용자");
		assertThat(fileImageService.isOwnedBy(fileId, uploaderId)).isTrue();
		assertThat(fileImageService.isOwnedBy(fileId, otherUserId)).isFalse();
		assertThatThrownBy(() -> fileImageService.requireOwnedFile(fileId, otherUserId))
				.isInstanceOf(BusinessException.class).extracting(ex -> ((BusinessException) ex).errorCode())
				.isEqualTo(ErrorCode.FILE_NOT_FOUND);
		assertThat(fileImageService.findSignedUrls(List.of(fileId, 987654321L))).containsOnlyKeys(fileId);

		var stored = inMemoryStorage.getObject(asset.getStorageKey()).orElseThrow();
		assertThat(stored.contentType()).isEqualTo("image/webp");
		assertThat(WebpThumbnailProcessor.isWebpMagic(stored.content())).isTrue();

		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stored.content()));
		assertThat(decoded.getWidth()).isEqualTo(480);
		assertThat(decoded.getHeight()).isEqualTo(640);

		assertThat(fileImageService.resolveSignedUrl(fileId)).contains(asset.getStorageKey());

		fileImageService.deleteFile(fileId);
		assertThat(fileAssetRepository.findById(fileId)).isEmpty();
		assertThat(inMemoryStorage.contains(asset.getStorageKey())).isFalse();
		assertThatThrownBy(() -> fileImageService.resolveSignedUrl(fileId)).isInstanceOf(BusinessException.class)
				.extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.FILE_NOT_FOUND);
	}

	@Test
	void 손상된바이트비허용MIME및5MB초과파일_직접업로드를요청하면_400과413으로거부하고저장하지않는다() throws Exception {
		String accessToken = jwtTokenProvider.issueAccessToken(insertUser("upload2@ziper.test", "업로더2"));
		MockMultipartFile corrupt = new MockMultipartFile("file", "broken.jpg", "image/jpeg",
				"corrupt-data".getBytes(StandardCharsets.UTF_8));
		MockMultipartFile disallowedMime = new MockMultipartFile("file", "anim.gif", "image/gif",
				createImageBytes(400, 400, "png"));
		MockMultipartFile oversized = new MockMultipartFile("file", "huge.jpg", "image/jpeg",
				new byte[5 * 1024 * 1024 + 1]);

		mvc.perform(multipart("/api/v1/files/images").file(corrupt).header(HttpHeaders.AUTHORIZATION,
				"Bearer " + accessToken)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_IMAGE_FILE"));

		mvc.perform(multipart("/api/v1/files/images").file(disallowedMime).header(HttpHeaders.AUTHORIZATION,
				"Bearer " + accessToken)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_IMAGE_FILE"));

		mvc.perform(multipart("/api/v1/files/images").file(oversized).header(HttpHeaders.AUTHORIZATION,
				"Bearer " + accessToken)).andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.code").value("IMAGE_FILE_TOO_LARGE"));

		assertThat(fileAssetRepository.count()).isZero();
		assertThat(inMemoryStorage.size()).isZero();
	}

	@Test
	void 미인증상태와서블릿용량초과상황_파일업로드를요청하면_401과413오류코드로응답한다() throws Exception {
		MockMultipartFile validPng = new MockMultipartFile("file", "photo.png", "image/png",
				createImageBytes(500, 500, "png"));
		mvc.perform(multipart("/api/v1/files/images").file(validPng)).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		MockMvc standalone = MockMvcBuilders.standaloneSetup(new OversizedMultipartEndpoint())
				.setControllerAdvice(new ApiExceptionHandler(new ProblemFactory())).build();
		standalone.perform(multipart("/oversized-test")).andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.code").value("IMAGE_FILE_TOO_LARGE"));
	}

	private static byte[] createImageBytes(int width, int height, String format) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(Color.DARK_GRAY);
			graphics.fillRect(0, 0, width, height);
			graphics.setColor(Color.ORANGE);
			graphics.fillRect(width / 4, height / 4, width / 2, height / 2);
		} finally {
			graphics.dispose();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, format, out);
		return out.toByteArray();
	}

	@RestController
	static class OversizedMultipartEndpoint {
		@PostMapping("/oversized-test")
		void upload() {
			throw new MaxUploadSizeExceededException(5_242_880L);
		}
	}
}
