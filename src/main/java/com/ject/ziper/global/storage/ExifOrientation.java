package com.ject.ziper.global.storage;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;

/**
 * Minimal EXIF Orientation (tag 0x0112) support for JPEG. Only that one tag is
 * needed, so a small bounds-checked parser over the already size-limited upload
 * bytes is used instead of a metadata library dependency. Anything malformed or
 * absent yields orientation 1 (no transform).
 */
final class ExifOrientation {
	static final int NORMAL = 1;

	private static final int TAG_ORIENTATION = 0x0112;
	private static final int TYPE_SHORT = 3;

	private ExifOrientation() {
	}

	static int read(byte[] jpeg) {
		if (jpeg == null || jpeg.length < 4 || u8(jpeg, 0) != 0xFF || u8(jpeg, 1) != 0xD8) {
			return NORMAL;
		}
		int pos = 2;
		while (pos + 4 <= jpeg.length) {
			if (u8(jpeg, pos) != 0xFF) {
				return NORMAL;
			}
			int marker = u8(jpeg, pos + 1);
			if (marker == 0xFF) {
				pos++;
				continue;
			}
			if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01) {
				pos += 2;
				continue;
			}
			if (marker == 0xDA || marker == 0xD9) {
				return NORMAL;
			}
			int length = (u8(jpeg, pos + 2) << 8) | u8(jpeg, pos + 3);
			int segmentEnd = pos + 2 + length;
			if (length < 2 || segmentEnd > jpeg.length) {
				return NORMAL;
			}
			if (marker == 0xE1 && isExifHeader(jpeg, pos + 4, segmentEnd)) {
				return readTiffOrientation(jpeg, pos + 10, segmentEnd);
			}
			pos = segmentEnd;
		}
		return NORMAL;
	}

	static BufferedImage apply(BufferedImage source, int orientation) {
		if (orientation <= NORMAL || orientation > 8) {
			return source;
		}
		int w = source.getWidth();
		int h = source.getHeight();
		boolean swap = orientation >= 5;
		AffineTransform transform = switch (orientation) {
			case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
			case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
			case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
			case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
			case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
			case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
			default -> new AffineTransform(0, -1, 1, 0, 0, w);
		};
		BufferedImage output = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = output.createGraphics();
		try {
			graphics.drawImage(source, transform, null);
		} finally {
			graphics.dispose();
		}
		return output;
	}

	private static boolean isExifHeader(byte[] data, int start, int end) {
		return start + 6 <= end && data[start] == 'E' && data[start + 1] == 'x' && data[start + 2] == 'i'
				&& data[start + 3] == 'f' && data[start + 4] == 0 && data[start + 5] == 0;
	}

	private static int readTiffOrientation(byte[] data, int tiff, int end) {
		if (tiff + 8 > end) {
			return NORMAL;
		}
		boolean littleEndian;
		if (data[tiff] == 'I' && data[tiff + 1] == 'I') {
			littleEndian = true;
		} else if (data[tiff] == 'M' && data[tiff + 1] == 'M') {
			littleEndian = false;
		} else {
			return NORMAL;
		}
		long ifdOffset = u32(data, tiff + 4, littleEndian);
		long ifd = tiff + ifdOffset;
		if (ifdOffset < 8 || ifd + 2 > end) {
			return NORMAL;
		}
		int entries = u16(data, (int) ifd, littleEndian);
		for (int i = 0; i < entries; i++) {
			long entry = ifd + 2 + 12L * i;
			if (entry + 12 > end) {
				return NORMAL;
			}
			if (u16(data, (int) entry, littleEndian) == TAG_ORIENTATION) {
				int type = u16(data, (int) entry + 2, littleEndian);
				long count = u32(data, (int) entry + 4, littleEndian);
				int value = u16(data, (int) entry + 8, littleEndian);
				return type == TYPE_SHORT && count == 1 && value >= 1 && value <= 8 ? value : NORMAL;
			}
		}
		return NORMAL;
	}

	private static int u8(byte[] data, int index) {
		return data[index] & 0xFF;
	}

	private static int u16(byte[] data, int index, boolean littleEndian) {
		return littleEndian
				? u8(data, index) | (u8(data, index + 1) << 8)
				: (u8(data, index) << 8) | u8(data, index + 1);
	}

	private static long u32(byte[] data, int index, boolean littleEndian) {
		long low = u16(data, index, littleEndian);
		long high = u16(data, index + 2, littleEndian);
		return littleEndian ? low | (high << 16) : (low << 16) | high;
	}
}
