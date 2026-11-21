package com.ject.ziper.global.storage;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.stereotype.Component;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

@Component
public class WebpThumbnailProcessor {
	public static final int TARGET_WIDTH = 480;
	public static final int TARGET_HEIGHT = 640;
	public static final float WEBP_QUALITY = 0.78f;
	public static final String WEBP_CONTENT_TYPE = "image/webp";

	private static final int MAX_SOURCE_DIMENSION = 10_000;
	private static final long MAX_SOURCE_PIXELS = 40_000_000L;
	private static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
	private static final Set<String> ALLOWED_FORMAT_NAMES = Set.of("jpeg", "jpg", "png", "webp");

	public ProcessedThumbnail process(byte[] rawBytes, String declaredContentType, long maxFileSizeBytes) {
		if (rawBytes == null || rawBytes.length == 0) {
			throw new BusinessException(ErrorCode.INVALID_IMAGE_FILE);
		}
		if (rawBytes.length > maxFileSizeBytes) {
			throw new BusinessException(ErrorCode.IMAGE_FILE_TOO_LARGE);
		}
		if (declaredContentType != null && !declaredContentType.isBlank()) {
			String normalizedMime = normalizeMimeType(declaredContentType);
			if (!ALLOWED_MIME_TYPES.contains(normalizedMime)) {
				throw new BusinessException(ErrorCode.INVALID_IMAGE_FILE);
			}
		}

		// ImageIO ignores EXIF Orientation, so phone photos would otherwise be rotated
		// and cropped the wrong way.
		BufferedImage source = ExifOrientation.apply(decodeValidatedImage(rawBytes), ExifOrientation.read(rawBytes));
		BufferedImage cropped = centerCropPortrait4to3(source);
		BufferedImage resized = resizeWithoutUpscaling(cropped);
		byte[] webpBytes = encodeWebp(resized);
		return new ProcessedThumbnail(webpBytes, resized.getWidth(), resized.getHeight(), WEBP_CONTENT_TYPE);
	}

	private BufferedImage decodeValidatedImage(byte[] rawBytes) {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(rawBytes))) {
			if (input == null) {
				throw new BusinessException(ErrorCode.INVALID_IMAGE_FILE);
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				throw new BusinessException(ErrorCode.INVALID_IMAGE_FILE);
			}
			ImageReader reader = readers.next();
			try {
				String format = reader.getFormatName();
				if (format == null || !ALLOWED_FORMAT_NAMES.contains(format.trim().toLowerCase(Locale.ROOT))) {
					throw new BusinessException(ErrorCode.INVALID_IMAGE_FILE);
				}
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width <= 0 || height <= 0 || width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION
						|| (long) width * (long) height > MAX_SOURCE_PIXELS) {
					throw new BusinessException(ErrorCode.INVALID_IMAGE_FILE);
				}
				BufferedImage image = reader.read(0);
				if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
					throw new BusinessException(ErrorCode.INVALID_IMAGE_FILE);
				}
				return image;
			} finally {
				reader.dispose();
			}
		} catch (BusinessException exception) {
			throw exception;
		} catch (IOException | RuntimeException exception) {
			throw new BusinessException(ErrorCode.INVALID_IMAGE_FILE);
		}
	}

	private BufferedImage centerCropPortrait4to3(BufferedImage source) {
		int srcWidth = source.getWidth();
		int srcHeight = source.getHeight();
		int cropWidth;
		int cropHeight;
		int cropX;
		int cropY;

		// 4:3 vertical portrait ratio => width : height = 3 : 4
		if ((long) srcWidth * 4L > (long) srcHeight * 3L) {
			cropHeight = srcHeight;
			cropWidth = Math.max(1, Math.min(srcWidth, (int) Math.round(srcHeight * 3.0 / 4.0)));
			cropX = (srcWidth - cropWidth) / 2;
			cropY = 0;
		} else {
			cropWidth = srcWidth;
			cropHeight = Math.max(1, Math.min(srcHeight, (int) Math.round(srcWidth * 4.0 / 3.0)));
			cropX = 0;
			cropY = (srcHeight - cropHeight) / 2;
		}
		return source.getSubimage(cropX, cropY, cropWidth, cropHeight);
	}

	private BufferedImage resizeWithoutUpscaling(BufferedImage cropped) {
		int cropWidth = cropped.getWidth();
		int cropHeight = cropped.getHeight();
		int targetWidth;
		int targetHeight;
		if (cropWidth > TARGET_WIDTH || cropHeight > TARGET_HEIGHT) {
			targetWidth = TARGET_WIDTH;
			targetHeight = TARGET_HEIGHT;
		} else {
			targetWidth = cropWidth;
			targetHeight = cropHeight;
		}

		BufferedImage output = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = output.createGraphics();
		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, targetWidth, targetHeight);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.drawImage(cropped, 0, 0, targetWidth, targetHeight, null);
		} finally {
			graphics.dispose();
		}
		return output;
	}

	private byte[] encodeWebp(BufferedImage image) {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType(WEBP_CONTENT_TYPE);
		if (!writers.hasNext()) {
			throw new IllegalStateException("WebP ImageIO writer is not available");
		}
		ImageWriter writer = writers.next();
		try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
				ImageOutputStream output = ImageIO.createImageOutputStream(baos)) {
			writer.setOutput(output);
			ImageWriteParam writeParam = writer.getDefaultWriteParam();
			if (writeParam.canWriteCompressed()) {
				writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
				String[] compressionTypes = writeParam.getCompressionTypes();
				if (compressionTypes != null && compressionTypes.length > 0) {
					String lossyType = compressionTypes[0];
					for (String type : compressionTypes) {
						if ("Lossy".equalsIgnoreCase(type)) {
							lossyType = type;
							break;
						}
					}
					writeParam.setCompressionType(lossyType);
				}
				writeParam.setCompressionQuality(WEBP_QUALITY);
			}
			writer.write(null, new IIOImage(image, null, null), writeParam);
			output.flush();
			byte[] bytes = baos.toByteArray();
			if (!isWebpMagic(bytes)) {
				throw new IllegalStateException("Encoded output is not valid WebP");
			}
			return bytes;
		} catch (IOException exception) {
			throw new IllegalStateException("Failed to encode WebP thumbnail", exception);
		} finally {
			writer.dispose();
		}
	}

	public static boolean isWebpMagic(byte[] bytes) {
		return bytes != null && bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
				&& bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
	}

	private static String normalizeMimeType(String contentType) {
		int semicolon = contentType.indexOf(';');
		String base = semicolon >= 0 ? contentType.substring(0, semicolon) : contentType;
		return base.trim().toLowerCase(Locale.ROOT);
	}

	public record ProcessedThumbnail(byte[] bytes, int width, int height, String contentType) {
	}
}
