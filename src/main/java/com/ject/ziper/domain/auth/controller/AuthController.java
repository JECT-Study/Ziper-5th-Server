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
import com.ject.ziper.domain.auth.dto.OAuthLoginRequest;
import com.ject.ziper.domain.auth.dto.OAuthLoginResponse;
import com.ject.ziper.domain.auth.dto.TokenReissueRequest;
import com.ject.ziper.domain.auth.dto.TokenResponse;
import com.ject.ziper.domain.auth.service.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
	private final AuthService authService;

	public AuthController(AuthService authService) {
		this.authService = authService;
	}

	@PostMapping("/oauth/{provider}")
	public ResponseEntity<OAuthLoginResponse> loginWithOAuth(@PathVariable String provider,
			@Valid @RequestBody OAuthLoginRequest request,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		return ResponseEntity.ok(authService.loginWithOAuth(provider, request.token(), userAgent));
	}

	@PostMapping("/reissue")
	public ResponseEntity<TokenResponse> reissue(@Valid @RequestBody TokenReissueRequest request,
			@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
		return ResponseEntity.ok(authService.reissue(request.refreshToken(), userAgent));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest request) {
		authService.logout(request.refreshToken());
		return ResponseEntity.noContent().build();
	}
}
