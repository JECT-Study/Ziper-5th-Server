package com.ject.ziper.domain.auth.controller;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.ject.ziper.domain.auth.dto.OAuthLoginResponse;
import com.ject.ziper.domain.auth.dto.TokenResponse;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.security.SecurityConfiguration;
import com.ject.ziper.global.security.SecurityProperties;

@Component
public class SessionTokenTransport {
	static final String COOKIE_NAME = "ziper_refresh_token";
	private static final String COOKIE_PATH = "/api/v1/auth";

	private final SecurityProperties security;

	public SessionTokenTransport(SecurityProperties security) {
		this.security = security;
	}

	boolean useCookie(HttpHeaders headers) {
		String mode = headers.getFirst(SecurityConfiguration.REFRESH_TOKEN_MODE_HEADER);
		if (mode == null) {
			return false;
		}
		if (!"cookie".equals(mode)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		String origin = headers.getOrigin();
		if (origin == null || !security.allowedOrigins().contains(origin)) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		return true;
	}

	String refreshToken(boolean useCookie, String bodyToken, String cookieToken) {
		if (!useCookie) {
			if (bodyToken == null || bodyToken.isBlank()) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST);
			}
			return bodyToken;
		}
		if (bodyToken != null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST);
		}
		if (cookieToken == null || cookieToken.isBlank()) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return cookieToken;
	}

	ResponseEntity<TokenResponse> respond(HttpStatus status, boolean useCookie, TokenResponse tokens) {
		var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
		if (!useCookie) {
			return response.body(tokens);
		}
		return response
				.header(HttpHeaders.SET_COOKIE, cookie(tokens.refreshToken(), security.refreshTokenExpirationSeconds()))
				.body(tokens.withoutRefreshToken());
	}

	ResponseEntity<OAuthLoginResponse> respond(HttpStatus status, boolean useCookie, OAuthLoginResponse login) {
		var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
		if (!useCookie || login.refreshToken() == null) {
			return response.body(login);
		}
		return response
				.header(HttpHeaders.SET_COOKIE, cookie(login.refreshToken(), security.refreshTokenExpirationSeconds()))
				.body(login.withoutRefreshToken());
	}

	/** 쿠키 모드에서는 로그아웃 처리 결과와 무관하게 만료 쿠키를 먼저 응답에 싣는다. */
	void expireCookie(boolean useCookie, HttpServletResponse response) {
		if (useCookie) {
			response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0));
		}
	}

	ResponseEntity<Void> loggedOut() {
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private static String cookie(String value, long maxAge) {
		return ResponseCookie.from(COOKIE_NAME, value).httpOnly(true).secure(true).sameSite("Lax").path(COOKIE_PATH)
				.maxAge(maxAge).build().toString();
	}
}
