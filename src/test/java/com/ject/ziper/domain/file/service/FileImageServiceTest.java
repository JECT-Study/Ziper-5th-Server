package com.ject.ziper.domain.file.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.ject.ziper.domain.file.entity.FileAsset;
import com.ject.ziper.domain.file.repository.FileAssetRepository;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.storage.InMemoryObjectStorageClient;
import com.ject.ziper.global.storage.ObjectStorageClient;
import com.ject.ziper.global.storage.StorageProperties;
import com.ject.ziper.global.storage.WebpThumbnailProcessor;

class FileImageServiceTest {
	private final StorageProperties properties = StorageProperties.defaultsForMemory();

	@Test
	void 객체업로드후DB저장이실패하면_직접업로드를요청할때_업로드한객체를삭제하고원래예외를전파한다() throws Exception {
		InMemoryObjectStorageClient storage = new InMemoryObjectStorageClient();
		FileAssetRepository repository = mock(FileAssetRepository.class);
		IllegalStateException dbFailure = new IllegalStateException("db down");
		when(repository.save(any())).thenThrow(dbFailure);
		FileImageService service = new FileImageService(storage, new WebpThumbnailProcessor(), repository, properties);
		byte[] image = jpeg();

		assertThatThrownBy(() -> service.uploadDirectImage(1L, image, "a.jpg", "image/jpeg", 100L)).isSameAs(dbFailure);

		assertThat(storage.size()).isZero();
	}

	@Test
	void 보상삭제까지실패해도_직접업로드를요청하면_원래예외를그대로전파한다() throws Exception {
		InMemoryObjectStorageClient delegate = new InMemoryObjectStorageClient();
		ObjectStorageClient failingDelete = new ObjectStorageClient() {
			@Override
			public void putObject(String storageKey, byte[] content, String contentType) {
				delegate.putObject(storageKey, content, contentType);
			}

			@Override
			public String createSignedUrl(String storageKey, Duration expiresIn) {
				return delegate.createSignedUrl(storageKey, expiresIn);
			}

			@Override
			public Map<String, String> createSignedUrls(Collection<String> storageKeys, Duration expiresIn) {
				return delegate.createSignedUrls(storageKeys, expiresIn);
			}

			@Override
			public void deleteObject(String storageKey) {
				throw new IllegalStateException("storage down");
			}
		};
		FileAssetRepository repository = mock(FileAssetRepository.class);
		IllegalStateException dbFailure = new IllegalStateException("db down");
		when(repository.save(any())).thenThrow(dbFailure);
		FileImageService service = new FileImageService(failingDelete, new WebpThumbnailProcessor(), repository,
				properties);
		byte[] image = jpeg();

		assertThatThrownBy(() -> service.uploadDirectImage(1L, image, "a.jpg", "image/jpeg", 100L)).isSameAs(dbFailure);
	}

	@Test
	void 여러파일ID를조회하는상황_일괄서명을요청하면_DB조회와저장소호출을각각한번만수행하고서명실패키는제외한다() {
		FileAssetRepository repository = mock(FileAssetRepository.class);
		when(repository.findAllById(any()))
				.thenReturn(List.of(asset(1L, "k/1.webp"), asset(2L, "k/2.webp"), asset(3L, "k/3.webp")));
		ObjectStorageClient storage = mock(ObjectStorageClient.class);
		Map<String, String> signed = new LinkedHashMap<>();
		signed.put("k/1.webp", "https://signed/1");
		signed.put("k/3.webp", "https://signed/3");
		when(storage.createSignedUrls(any(), any())).thenReturn(signed);
		FileImageService service = new FileImageService(storage, new WebpThumbnailProcessor(), repository, properties);

		Map<Long, String> result = service.findSignedUrls(List.of(1L, 2L, 3L, 3L, 99L));

		assertThat(result).containsOnlyKeys(1L, 3L).containsEntry(1L, "https://signed/1");
		verify(repository, times(1)).findAllById(any());
		verify(storage, times(1)).createSignedUrls(any(), any());
		verify(storage, never()).createSignedUrl(any(), any());
		assertThat(service.findSignedUrls(List.of())).isEmpty();
	}

	@Test
	void 단건서명URL조회_요청하면_일괄조회로위임하고없으면비어있다() {
		FileAssetRepository repository = mock(FileAssetRepository.class);
		when(repository.findAllById(any())).thenReturn(List.of(asset(7L, "k/7.webp")));
		ObjectStorageClient storage = mock(ObjectStorageClient.class);
		when(storage.createSignedUrls(any(), any())).thenReturn(Map.of("k/7.webp", "https://signed/7"));
		FileImageService service = new FileImageService(storage, new WebpThumbnailProcessor(), repository, properties);

		assertThat(service.findSignedUrl(7L)).contains("https://signed/7");
		assertThat(service.findSignedUrl(null)).isEmpty();
		when(repository.findAllById(any())).thenReturn(List.of());
		assertThat(service.findSignedUrl(8L)).isEmpty();
		assertThatThrownBy(() -> service.resolveSignedUrl(8L)).isInstanceOf(BusinessException.class)
				.extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.FILE_NOT_FOUND);
	}

	@Test
	void 소유하지않은파일ID_소유확인하면_거부하고404를던진다() {
		FileAssetRepository repository = mock(FileAssetRepository.class);
		when(repository.existsByIdAndOwnerUserId(5L, 10L)).thenReturn(true);
		FileImageService service = new FileImageService(new InMemoryObjectStorageClient(), new WebpThumbnailProcessor(),
				repository, properties);

		assertThat(service.isOwnedBy(5L, 10L)).isTrue();
		assertThat(service.isOwnedBy(5L, 11L)).isFalse();
		service.requireOwnedFile(5L, 10L);
		assertThatThrownBy(() -> service.requireOwnedFile(5L, 11L)).isInstanceOf(BusinessException.class)
				.extracting(ex -> ((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.FILE_NOT_FOUND);
	}

	private static FileAsset asset(long id, String key) {
		FileAsset asset = FileAsset.create(key, 1L, "f.webp", "image/webp", 10, Instant.now());
		ReflectionTestUtils.setField(asset, "id", id);
		return asset;
	}

	private static byte[] jpeg() throws IOException {
		BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setColor(Color.GREEN);
			graphics.fillRect(0, 0, 200, 200);
		} finally {
			graphics.dispose();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "jpg", out);
		return out.toByteArray();
	}
}
