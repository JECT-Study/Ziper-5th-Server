package com.ject.ziper.domain.tag.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.tag.dto.CreateTagRequest;
import com.ject.ziper.domain.tag.dto.TagListResponse;
import com.ject.ziper.domain.tag.dto.TagResponse;
import com.ject.ziper.domain.tag.service.TagService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/tags")
@Tag(name = "해시태그", description = "기본 계절 태그 및 사용자별 커스텀 해시태그 조회·생성·삭제 API")
public class TagController {
	private final TagService tagService;

	public TagController(TagService tagService) {
		this.tagService = tagService;
	}

	@GetMapping
	@Operation(summary = "해시태그 목록 조회", description = "공용 기본 계절 태그 4종(봄·여름·가을·겨울)과 현재 사용자의 커스텀 해시태그를 기본 태그 우선·생성순으로 조회합니다.")
	public ResponseEntity<TagListResponse> listTags(@AuthenticationPrincipal Jwt jwt) {
		Long userId = Long.valueOf(jwt.getSubject());
		return ResponseEntity.ok(tagService.listTags(userId));
	}

	@PostMapping
	@Operation(summary = "커스텀 해시태그 생성", description = "선행 #과 앞뒤 공백을 제거한 1~6자 커스텀 해시태그를 생성합니다. 기본 4종 또는 본인 커스텀 태그와 중복되면 409를 반환합니다.")
	public ResponseEntity<TagResponse> createTag(@AuthenticationPrincipal Jwt jwt,
			@RequestBody CreateTagRequest request) {
		Long userId = Long.valueOf(jwt.getSubject());
		TagResponse created = tagService.createTag(userId, request.title());
		return ResponseEntity.status(HttpStatus.CREATED).body(created);
	}

	@DeleteMapping("/{tagId}")
	@Operation(summary = "커스텀 해시태그 삭제", description = "본인 소유의 커스텀 해시태그를 삭제합니다. cardId를 함께 전달하면 다른 카드에서도 사용 중인 태그는 해당 카드와의 연결만 해제하고, 단독 사용 중이거나 cardId가 없으면 태그 자체를 삭제합니다.")
	public ResponseEntity<Void> deleteTag(@AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "삭제할 커스텀 해시태그 ID", example = "5") @PathVariable Long tagId,
			@Parameter(description = "특정 상품 카드 편집 화면에서 삭제 시 대상 카드 ID (미지정 시 전역 삭제)", example = "10") @RequestParam(required = false) Long cardId) {
		Long userId = Long.valueOf(jwt.getSubject());
		tagService.deleteTag(userId, tagId, cardId);
		return ResponseEntity.noContent().build();
	}
}
