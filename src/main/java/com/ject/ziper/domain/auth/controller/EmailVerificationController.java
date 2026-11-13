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
						current.remainingSeconds(), current.verified()));
	}

	public record IssueResponse(UUID flowId) {
	}

	public record StatusResponse(int failedAttempts, int remainingAttempts, Instant expiresAt, long remainingSeconds,
			boolean verified) {
	}
}
