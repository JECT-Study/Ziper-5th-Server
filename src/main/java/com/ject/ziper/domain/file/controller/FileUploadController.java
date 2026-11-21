package com.ject.ziper.domain.file.controller;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.ject.ziper.domain.file.dto.ImageUploadResponse;
import com.ject.ziper.domain.file.service.FileImageService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "파일 및 이미지 업로드", description = "상품 카드 이미지 직접 업로드(B-2 WebP 썸네일 변환 및 비공개 Object Storage 저장) API")
public class FileUploadController {
	private final FileImageService fileImageService;

	public FileUploadController(FileImageService fileImageService) {
		this.fileImageService = fileImageService;
	}

	@PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "상품 카드 이미지 직접 업로드", description = "JPEG·PNG·WebP 이미지(최대 5MB)를 4:3 세로 비율(최대 480x640) WebP 썸네일로 정규화해 비공개 Object Storage에 저장하고 단기 서명 URL을 반환합니다.")
	public ResponseEntity<ImageUploadResponse> uploadImage(@AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "업로드할 이미지 파일 (image/jpeg, image/png, image/webp, 최대 5MB)") @RequestPart("file") MultipartFile file)
			throws IOException {
		if (file == null || file.isEmpty()) {
			throw new BusinessException(ErrorCode.INVALID_IMAGE_FILE);
		}
		ImageUploadResponse response = fileImageService.uploadDirectImage(file.getBytes(), file.getOriginalFilename(),
				file.getContentType(), file.getSize());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}
}
