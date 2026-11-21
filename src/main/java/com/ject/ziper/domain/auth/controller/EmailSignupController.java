package com.ject.ziper.domain.auth.controller;

import jakarta.validation.Valid;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.auth.dto.EmailSignupRequest;
import com.ject.ziper.domain.auth.dto.TokenResponse;
import com.ject.ziper.domain.auth.service.EmailSignupService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@ConditionalOnProperty(prefix = "app.mail", name = "enabled", havingValue = "true")
@SecurityRequirements
@Tag(name = "이메일 인증")
public class EmailSignupController {
	private final EmailSignupService service;
	private final SessionTokenTransport transport;

	public EmailSignupController(EmailSignupService service, SessionTokenTransport transport) {
		this.service = service;
		this.transport = transport;
	}

	@PostMapping("/api/v1/auth/email/signup")
	@Operation(summary = "이메일 회원 가입", description = "인증을 완료한 이메일 흐름으로 비밀번호·닉네임·약관 동의를 받아 가입하고 자체 Access/Refresh JWT를 발급합니다. 흐름은 한 번만 사용할 수 있으며 인증 후 30분 안에 가입해야 합니다.")
	public ResponseEntity<TokenResponse> signup(@Valid @RequestBody EmailSignupRequest body,
			@RequestHeader HttpHeaders headers) {
		boolean useCookie = transport.useCookie(headers);
		TokenResponse tokens = service.signup(body.flowId(), body.password(), body.nickname(),
				body.agreements().marketing(), headers.getFirst(HttpHeaders.USER_AGENT));
		return transport.respond(HttpStatus.CREATED, useCookie, tokens);
	}
}
