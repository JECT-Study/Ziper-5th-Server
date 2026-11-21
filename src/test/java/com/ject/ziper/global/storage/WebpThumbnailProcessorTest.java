package com.ject.ziper.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.storage.WebpThumbnailProcessor.ProcessedThumbnail;

class WebpThumbnailProcessorTest {
	private final WebpThumbnailProcessor processor = new WebpThumbnailProcessor();

	@Test
	void 고해상도가로JPEG이미지_썸네일로변환하면_480x640세로4대3WebP바이트를생성한다() throws Exception {
		byte[] jpeg = createSampleImage(1200, 800, BufferedImage.TYPE_INT_RGB, "jpg");

		ProcessedThumbnail result = processor.process(jpeg, "image/jpeg",
				StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES);

		assertThat(result.contentType()).isEqualTo("image/webp");
		assertThat(result.width()).isEqualTo(480);
		assertThat(result.height()).isEqualTo(640);
		assertThat(WebpThumbnailProcessor.isWebpMagic(result.bytes())).isTrue();

		BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result.bytes()));
		assertThat(decoded).isNotNull();
		assertThat(decoded.getWidth()).isEqualTo(480);
		assertThat(decoded.getHeight()).isEqualTo(640);
	}

	@Test
	void 투명배경세로PNG이미지_썸네일로변환하면_480x640WebP로정규화한다() throws Exception {
		byte[] png = createSampleImage(600, 1200, BufferedImage.TYPE_INT_ARGB, "png");

		ProcessedThumbnail result = processor.process(png, "image/png; charset=binary",
				StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES);

		assertThat(result.contentType()).isEqualTo("image/webp");
		assertThat(result.width()).isEqualTo(480);
		assertThat(result.height()).isEqualTo(640);
		assertThat(WebpThumbnailProcessor.isWebpMagic(result.bytes())).isTrue();
	}

	@Test
	void 작은해상도정사각형이미지_썸네일로변환하면_확대없이세로4대3비율로크롭한다() throws Exception {
		byte[] png = createSampleImage(300, 300, BufferedImage.TYPE_INT_RGB, "png");

		ProcessedThumbnail result = processor.process(png, "image/png", StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES);

		assertThat(result.width()).isEqualTo(225);
		assertThat(result.height()).isEqualTo(300);
		assertThat(WebpThumbnailProcessor.isWebpMagic(result.bytes())).isTrue();
	}

	@Test
	void 손상된바이트나비허용MIME또는빈파일_썸네일변환을요청하면_INVALIDIMAGEFILE예외를발생시킨다() throws Exception {
		byte[] jpeg = createSampleImage(400, 400, BufferedImage.TYPE_INT_RGB, "jpg");
		byte[] corrupt = "not-an-image".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> processor.process(new byte[0], "image/jpeg", 1024))
				.isInstanceOf(BusinessException.class).extracting(ex -> ((BusinessException) ex).errorCode())
				.isEqualTo(ErrorCode.INVALID_IMAGE_FILE);
		assertThatThrownBy(() -> processor.process(corrupt, "image/jpeg", 1024)).isInstanceOf(BusinessException.class)
				.extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.INVALID_IMAGE_FILE);
		assertThatThrownBy(() -> processor.process(jpeg, "image/gif", StorageProperties.DEFAULT_MAX_FILE_SIZE_BYTES))
				.isInstanceOf(BusinessException.class).extracting(ex -> ((BusinessException) ex).errorCode())
				.isEqualTo(ErrorCode.INVALID_IMAGE_FILE);
	}

	@Test
	void 허용용량을초과한이미지_썸네일변환을요청하면_IMAGEFILETOOLARGE예외를발생시킨다() {
		byte[] oversized = new byte[1025];

		assertThatThrownBy(() -> processor.process(oversized, "image/jpeg", 1024)).isInstanceOf(BusinessException.class)
				.extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.IMAGE_FILE_TOO_LARGE);
	}

	static byte[] createSampleImage(int width, int height, int imageType, String format) throws IOException {
		BufferedImage image = new BufferedImage(width, height, imageType);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(new Color(45, 110, 180));
			graphics.fillRect(0, 0, width, height);
			graphics.setColor(new Color(230, 140, 60));
			graphics.fillOval(width / 4, height / 4, width / 2, height / 2);
		} finally {
			graphics.dispose();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, format, out);
		return out.toByteArray();
	}
}
