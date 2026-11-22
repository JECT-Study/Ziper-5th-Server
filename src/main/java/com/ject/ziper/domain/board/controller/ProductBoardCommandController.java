package com.ject.ziper.domain.board.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.board.dto.CreateBoardRequest;
import com.ject.ziper.domain.board.dto.CreateBoardResponse;
import com.ject.ziper.domain.board.dto.UpdateBoardRequest;
import com.ject.ziper.domain.board.dto.UpdateBoardResponse;
import com.ject.ziper.domain.board.service.ProductBoardCommandService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/boards")
@Tag(name = "보드", description = "상품 보드 생성·이름 수정·삭제 API")
public class ProductBoardCommandController {
	private static final String MAX_LONG_TEXT = String.valueOf(Long.MAX_VALUE);

	private final ProductBoardCommandService boardCommandService;

	public ProductBoardCommandController(ProductBoardCommandService boardCommandService) {
		this.boardCommandService = boardCommandService;
	}

	@PostMapping
	@Operation(summary = "새 보드 생성", description = "커스텀 상품 보드를 생성합니다. 보드 이름은 앞뒤 공백 제거 후 공백 포함 1~10자이며, 활성 보드 간 중복을 허용하지 않습니다.")
	public ResponseEntity<CreateBoardResponse> createBoard(@AuthenticationPrincipal Jwt jwt,
			@RequestBody CreateBoardRequest request) {
		CreateBoardResponse response = boardCommandService.createBoard(extractUserId(jwt), request.name());
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	@PatchMapping("/{boardId}")
	@Operation(summary = "보드 이름 수정", description = "소유한 활성 커스텀 보드의 이름을 수정합니다. 기본 보드(modifiable=false)는 수정할 수 없습니다.")
	public ResponseEntity<UpdateBoardResponse> renameBoard(@AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "수정할 보드 ID", example = "2") @PathVariable Long boardId,
			@RequestBody UpdateBoardRequest request) {
		UpdateBoardResponse response = boardCommandService.renameBoard(extractUserId(jwt), boardId, request.name());
		return ResponseEntity.ok(response);
	}

	@DeleteMapping("/{boardId}")
	@Operation(summary = "보드 삭제", description = "소유한 활성 커스텀 보드를 소프트 삭제하고 보드-상품 연결을 해제합니다. 다른 활성 보드에 연결되지 않은 고아 상품 카드만 30일 휴지통으로 이동합니다.")
	public ResponseEntity<Void> deleteBoard(@AuthenticationPrincipal Jwt jwt,
			@Parameter(description = "삭제할 보드 ID", example = "2") @PathVariable Long boardId) {
		boardCommandService.deleteBoard(extractUserId(jwt), boardId);
		return ResponseEntity.noContent().build();
	}

	private static Long extractUserId(Jwt jwt) {
		String subject = jwt.getSubject();
		if (subject == null || subject.isEmpty() || subject.length() > MAX_LONG_TEXT.length()
				|| !subject.chars().allMatch(Character::isDigit)) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		if (subject.length() == MAX_LONG_TEXT.length() && subject.compareTo(MAX_LONG_TEXT) > 0) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		long userId = Long.parseLong(subject);
		if (userId <= 0) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return userId;
	}
}
