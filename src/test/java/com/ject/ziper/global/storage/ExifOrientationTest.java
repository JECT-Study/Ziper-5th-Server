package com.ject.ziper.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.ject.ziper.global.storage.WebpThumbnailProcessor.ProcessedThumbnail;

class ExifOrientationTest {
	private final WebpThumbnailProcessor processor = new WebpThumbnailProcessor();

	@Test
	void EXIF방향태그없는가로JPEG_썸네일로변환하면_회전없이중앙3대4로크롭한다() throws Exception {
		byte[] jpeg = redLeftBlueRightJpeg(800, 400, 0, false);

		BufferedImage result = decode(processor.process(jpeg, "image/jpeg", 5_000_000L));

		assertThat(result.getWidth()).isEqualTo(300);
		assertThat(result.getHeight()).isEqualTo(400);
		assertThat(isRed(result.getRGB(30, 200))).isTrue();
		assertThat(isBlue(result.getRGB(270, 200))).isTrue();
	}

	@Test
	void 방향6휴대폰JPEG_썸네일로변환하면_시계방향90도회전후세로로크롭한다() throws Exception {
		byte[] jpeg = redLeftBlueRightJpeg(800, 400, 6, false);

		BufferedImage result = decode(processor.process(jpeg, "image/jpeg", 5_000_000L));

		assertThat(result.getWidth()).isEqualTo(400);
		assertThat(result.getHeight()).isEqualTo(533);
		assertThat(isRed(result.getRGB(200, 40))).as("원본 왼쪽(빨강)이 위쪽으로").isTrue();
		assertThat(isBlue(result.getRGB(200, 490))).as("원본 오른쪽(파랑)이 아래쪽으로").isTrue();
	}

	@Test
	void 방향8빅엔디안JPEG_썸네일로변환하면_반시계방향90도회전한다() throws Exception {
		byte[] jpeg = redLeftBlueRightJpeg(800, 400, 8, true);

		BufferedImage result = decode(processor.process(jpeg, "image/jpeg", 5_000_000L));

		assertThat(result.getWidth()).isEqualTo(400);
		assertThat(result.getHeight()).isEqualTo(533);
		assertThat(isBlue(result.getRGB(200, 40))).isTrue();
		assertThat(isRed(result.getRGB(200, 490))).isTrue();
	}

	@Test
	void 방향3JPEG_썸네일로변환하면_180도회전해좌우가바뀐다() throws Exception {
		byte[] jpeg = redLeftBlueRightJpeg(800, 400, 3, false);

		BufferedImage result = decode(processor.process(jpeg, "image/jpeg", 5_000_000L));

		assertThat(result.getWidth()).isEqualTo(300);
		assertThat(result.getHeight()).isEqualTo(400);
		assertThat(isBlue(result.getRGB(30, 200))).isTrue();
		assertThat(isRed(result.getRGB(270, 200))).isTrue();
	}

	@Test
	void 손상되거나범위밖인EXIF_방향을읽으면_회전없음으로처리한다() throws Exception {
		byte[] jpeg = redLeftBlueRightJpeg(800, 400, 6, false);
		byte[] truncated = Arrays.copyOf(jpeg, 30);

		assertThat(ExifOrientation.read(jpeg)).isEqualTo(6);
		assertThat(ExifOrientation.read(truncated)).isEqualTo(ExifOrientation.NORMAL);
		assertThat(ExifOrientation.read(new byte[]{1, 2, 3})).isEqualTo(ExifOrientation.NORMAL);
		assertThat(ExifOrientation.read(redLeftBlueRightJpeg(800, 400, 9, false))).isEqualTo(ExifOrientation.NORMAL);
	}

	private static BufferedImage decode(ProcessedThumbnail thumbnail) throws IOException {
		return ImageIO.read(new ByteArrayInputStream(thumbnail.bytes()));
	}

	private static boolean isRed(int rgb) {
		Color color = new Color(rgb);
		return color.getRed() > 180 && color.getBlue() < 90;
	}

	private static boolean isBlue(int rgb) {
		Color color = new Color(rgb);
		return color.getBlue() > 180 && color.getRed() < 90;
	}

	/** Left half red, right half blue. orientation 0 writes no EXIF segment. */
	private static byte[] redLeftBlueRightJpeg(int width, int height, int orientation, boolean bigEndian)
			throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(Color.RED);
			graphics.fillRect(0, 0, width / 2, height);
			graphics.setColor(Color.BLUE);
			graphics.fillRect(width / 2, 0, width - width / 2, height);
		} finally {
			graphics.dispose();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		byte[] jpeg = out.toByteArray();
		if (orientation == 0) {
			return jpeg;
		}
		byte[] app1 = exifSegment(orientation, bigEndian);
		ByteArrayOutputStream withExif = new ByteArrayOutputStream();
		withExif.write(jpeg, 0, 2);
		withExif.write(app1);
		withExif.write(jpeg, 2, jpeg.length - 2);
		return withExif.toByteArray();
	}

	private static byte[] exifSegment(int orientation, boolean bigEndian) {
		ByteOrder order = bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
		ByteBuffer tiff = ByteBuffer.allocate(26).order(order);
		tiff.put((byte) (bigEndian ? 'M' : 'I')).put((byte) (bigEndian ? 'M' : 'I'));
		tiff.putShort((short) 42).putInt(8);
		tiff.putShort((short) 1);
		tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
		tiff.putInt(0);
		byte[] tiffBytes = tiff.array();
		ByteBuffer segment = ByteBuffer.allocate(2 + 2 + 6 + tiffBytes.length);
		segment.put((byte) 0xFF).put((byte) 0xE1);
		segment.putShort((short) (2 + 6 + tiffBytes.length));
		segment.put(new byte[]{'E', 'x', 'i', 'f', 0, 0});
		segment.put(tiffBytes);
		return segment.array();
	}
}
