package com.ject.ziper.domain.auth.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.auth.dto.LogoutRequest;
import com.ject.ziper.domain.auth.dto.OAuthCodeLoginRequest;
import com.ject.ziper.domain.auth.dto.OAuthLoginRequest;
import com.ject.ziper.domain.auth.dto.OAuthLoginResponse;
import com.ject.ziper.domain.auth.dto.TokenReissueRequest;
import com.ject.ziper.domain.auth.dto.TokenResponse;
import com.ject.ziper.domain.auth.service.AuthService;

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

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/oauth/{provider}")
	@Operation(summary = "소셜 토큰 직접 로그인 (ID Token / Access Token)", description = "모바일 네이티브 SDK 또는 Apple JS SDK가 발급받은 ID Token(Apple) 또는 Access Token(Naver·Kakao)을 검증해 자체 Access/Refresh JWT를 발급합니다.")
	public ResponseEntity<OAuthLoginResponse> loginWithOAuth(
			@Parameter(description = "OAuth 프로바이더 (apple | naver | kakao)", example = "apple") @PathVariable String provider,
			@Valid @RequestBody OAuthLoginRequest request,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		return ResponseEntity.ok(authService.loginWithOAuth(provider, request.token(), userAgent));
	}

	@PostMapping("/oauth/{provider}/code")
	@Operation(summary = "소셜 인가 코드 로그인 (Server-to-Server Authorization Code Grant)", description = "클라이언트가 받은 Authorization Code(code, redirectUri, state)로 서버가 프로바이더 토큰 엔드포인트와 직접 통신해 토큰을 교환·검증하고 자체 Access/Refresh JWT를 발급합니다.")
	public ResponseEntity<OAuthLoginResponse> loginWithOAuthCode(
			@Parameter(description = "OAuth 프로바이더 (apple | naver | kakao)", example = "kakao") @PathVariable String provider,
			@Valid @RequestBody OAuthCodeLoginRequest request,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		return ResponseEntity.ok(authService.loginWithOAuthCode(provider, request.code(), request.redirectUri(),
				request.state(), userAgent));
	}

	@PostMapping("/reissue")
	@Operation(summary = "자체 Access·Refresh 토큰 회전 재발급", description = "기존 Refresh Token의 SHA-256 해시를 검증한 뒤 새 Access/Refresh JWT를 발급하고 세션을 즉시 회전합니다.")
	public ResponseEntity<TokenResponse> reissue(@Valid @RequestBody TokenReissueRequest request,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		return ResponseEntity.ok(authService.reissue(request.refreshToken(), userAgent));
	}

	@PostMapping("/logout")
	@Operation(summary = "로그아웃 및 세션 폐기", description = "Refresh Token에 해당하는 세션을 삭제해 이후 재발급을 차단합니다.")
	public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
		authService.logout(request.refreshToken());
		return ResponseEntity.noContent().build();
	}
}
