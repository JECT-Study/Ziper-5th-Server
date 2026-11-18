package com.ject.ziper.domain.auth.controller;

import java.time.Instant;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.auth.dto.EmailCodeRequest;
import com.ject.ziper.domain.auth.dto.EmailVerificationRequest;
import com.ject.ziper.domain.auth.service.EmailVerificationService;
import com.ject.ziper.domain.auth.service.EmailVerificationService.VerificationStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/auth/email/verifications")
@ConditionalOnProperty(prefix = "app.mail", name = "enabled", havingValue = "true")
@SecurityRequirements
@Tag(name = "이메일 인증")
public class EmailVerificationController {
	private final EmailVerificationService service;

	public EmailVerificationController(EmailVerificationService service) {
		this.service = service;
	}

	@PostMapping
	@Operation(summary = "이메일 인증번호 발송")
	public ResponseEntity<IssueResponse> issue(@Valid @RequestBody EmailVerificationRequest body,
			HttpServletRequest request) {
		UUID flowId = service.issue(body.email(), request.getRemoteAddr());
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(new IssueResponse(flowId));
	}

	@PostMapping("/{flowId}/resend")
	@Operation(summary = "이메일 인증번호 재전송")
	public ResponseEntity<Void> resend(@PathVariable UUID flowId, HttpServletRequest request) {
		service.resend(flowId, request.getRemoteAddr());
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@PostMapping("/{flowId}/verify")
	@Operation(summary = "이메일 인증번호 확인")
	public ResponseEntity<Void> verify(@PathVariable UUID flowId, @Valid @RequestBody EmailCodeRequest body) {
		service.verify(flowId, body.code());
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@GetMapping("/{flowId}")
	@Operation(summary = "이메일 인증 상태 조회")
	public ResponseEntity<StatusResponse> status(@PathVariable UUID flowId) {
		VerificationStatus current = service.status(flowId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(new StatusResponse(current.failedAttempts(), current.remainingAttempts(), current.expiresAt(),
						current.remainingSeconds(), current.verified(), current.lockedUntil()));
	}

	@Schema(description = "이메일 인증번호 발송 응답")
	public record IssueResponse(
			@Schema(description = "생성된 이메일 인증 흐름 ID", example = "3f1c2a4e-8b7d-4c6a-9e1f-2a3b4c5d6e7f", requiredMode = RequiredMode.REQUIRED) UUID flowId) {
	}

	@Schema(description = "이메일 인증 상태 조회 응답")
	public record StatusResponse(
			@Schema(description = "현재 이메일 기준 누적 오입력 횟수", example = "1", requiredMode = RequiredMode.REQUIRED) int failedAttempts,
			@Schema(description = "잠금 전까지 남은 입력 시도 횟수", example = "4", requiredMode = RequiredMode.REQUIRED) int remainingAttempts,
			@Schema(description = "현재 인증번호 만료 시각 (UTC)", example = "2026-11-19T00:15:00Z", requiredMode = RequiredMode.REQUIRED) Instant expiresAt,
			@Schema(description = "만료까지 남은 시간 (초)", example = "175", requiredMode = RequiredMode.REQUIRED) long remainingSeconds,
			@Schema(description = "인증 완료 여부", example = "false", requiredMode = RequiredMode.REQUIRED) boolean verified,
			@Schema(description = "오입력 5회 초과 시 잠금 해제 시각 (UTC, 잠기지 않았으면 null)", example = "2026-11-19T00:45:00Z", requiredMode = RequiredMode.REQUIRED, nullable = true) Instant lockedUntil) {
	}
}
