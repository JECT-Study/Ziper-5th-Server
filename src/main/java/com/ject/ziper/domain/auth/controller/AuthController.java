package com.ject.ziper.domain.auth.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.auth.dto.EmailLoginRequest;
import com.ject.ziper.domain.auth.dto.LogoutRequest;
import com.ject.ziper.domain.auth.dto.OAuthCodeLoginRequest;
import com.ject.ziper.domain.auth.dto.OAuthLoginRequest;
import com.ject.ziper.domain.auth.dto.OAuthLoginResponse;
import com.ject.ziper.domain.auth.dto.OAuthSignupRequest;
import com.ject.ziper.domain.auth.dto.TokenReissueRequest;
import com.ject.ziper.domain.auth.dto.TokenResponse;
import com.ject.ziper.domain.auth.service.AuthService;
import com.ject.ziper.domain.auth.service.EmailLoginService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/auth")
@SecurityRequirements
@Tag(name = "소셜 OAuth 및 세션 인증", description = "ID Token/Access Token 직접 검증 로그인과 Server-to-Server Authorization Code 교환 로그인, 자체 JWT 재발급 및 로그아웃 API")
public class AuthController {
	private final AuthService authService;
	private final EmailLoginService emailLogin;
	private final SessionTokenTransport transport;

	public AuthController(AuthService authService, EmailLoginService emailLogin, SessionTokenTransport transport) {
		this.authService = authService;
		this.emailLogin = emailLogin;
		this.transport = transport;
	}

	@PostMapping("/oauth/{provider}")
	@Operation(summary = "소셜 토큰 직접 로그인", description = "소셜 토큰을 검증해 기존 회원은 로그인하고, 신규 회원에게는 약관 동의에 필요한 일회용 가입 토큰을 발급합니다.")
	public ResponseEntity<OAuthLoginResponse> loginWithOAuth(
			@Parameter(description = "OAuth 프로바이더 (apple | naver | kakao)", example = "apple") @PathVariable String provider,
			@Valid @RequestBody OAuthLoginRequest request, @RequestHeader HttpHeaders headers) {
		boolean useCookie = transport.useCookie(headers);
		return transport.respond(HttpStatus.OK, useCookie,
				authService.loginWithOAuth(provider, request.token(), headers.getFirst(HttpHeaders.USER_AGENT)));
	}

	@PostMapping("/oauth/{provider}/code")
	@Operation(summary = "소셜 인가 코드 로그인", description = "인가 코드를 교환하고 소셜 계정을 검증해 기존 회원은 로그인하고, 신규 회원에게는 일회용 가입 토큰을 발급합니다.")
	public ResponseEntity<OAuthLoginResponse> loginWithOAuthCode(
			@Parameter(description = "OAuth 프로바이더 (apple | naver | kakao)", example = "kakao") @PathVariable String provider,
			@Valid @RequestBody OAuthCodeLoginRequest request, @RequestHeader HttpHeaders headers) {
		boolean useCookie = transport.useCookie(headers);
		return transport.respond(HttpStatus.OK, useCookie, authService.loginWithOAuthCode(provider, request.code(),
				request.redirectUri(), request.state(), headers.getFirst(HttpHeaders.USER_AGENT)));
	}

	@PostMapping("/oauth/signup")
	@Operation(summary = "소셜 회원 가입 완료", description = "일회용 가입 토큰과 필수 약관 동의로 회원 가입을 완료하고 토큰을 발급합니다.")
	public ResponseEntity<OAuthLoginResponse> signupWithOAuth(@Valid @RequestBody OAuthSignupRequest request,
			@RequestHeader HttpHeaders headers) {
		boolean useCookie = transport.useCookie(headers);
		return transport.respond(HttpStatus.CREATED, useCookie, authService.signupWithOAuth(request.signupToken(),
				request.agreements().marketing(), headers.getFirst(HttpHeaders.USER_AGENT)));
	}

	@PostMapping("/email/login")
	@Operation(summary = "이메일·비밀번호 로그인", description = "가입한 이메일과 비밀번호를 검증하고 토큰과 현재 약관 동의 상태를 반환합니다.")
	public ResponseEntity<TokenResponse> loginWithEmail(@Valid @RequestBody EmailLoginRequest request,
			@RequestHeader HttpHeaders headers) {
		boolean useCookie = transport.useCookie(headers);
		return transport.respond(HttpStatus.OK, useCookie,
				emailLogin.login(request.email(), request.password(), headers.getFirst(HttpHeaders.USER_AGENT)));
	}

	@PostMapping("/reissue")
	@Operation(summary = "자체 Access·Refresh 토큰 회전 재발급", description = "Refresh Token을 요청 본문 또는 X-Refresh-Token-Mode: cookie 모드의 쿠키에서 검증하고 회전합니다.")
	public ResponseEntity<TokenResponse> reissue(@RequestBody(required = false) TokenReissueRequest request,
			@CookieValue(name = SessionTokenTransport.COOKIE_NAME, required = false) String cookieToken,
			@RequestHeader HttpHeaders headers) {
		boolean useCookie = transport.useCookie(headers);
		String token = transport.refreshToken(useCookie, request == null ? null : request.refreshToken(), cookieToken);
		return transport.respond(HttpStatus.OK, useCookie,
				authService.reissue(token, headers.getFirst(HttpHeaders.USER_AGENT)));
	}

	@PostMapping("/logout")
	@Operation(summary = "로그아웃 및 세션 폐기", description = "Refresh Token에 해당하는 세션을 삭제하고 쿠키 모드에서는 쿠키를 만료시킵니다.")
	public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest request,
			@CookieValue(name = SessionTokenTransport.COOKIE_NAME, required = false) String cookieToken,
			@RequestHeader HttpHeaders headers) {
		boolean useCookie = transport.useCookie(headers);
		authService.logout(
				transport.refreshToken(useCookie, request == null ? null : request.refreshToken(), cookieToken));
		return transport.loggedOut(useCookie);
	}
}
