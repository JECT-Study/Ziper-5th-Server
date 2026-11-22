package com.ject.ziper.domain.auth.service;

import java.time.Instant;
import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.auth.dto.TokenResponse;
import com.ject.ziper.domain.user.entity.User;
import com.ject.ziper.domain.user.entity.UserStatus;
import com.ject.ziper.domain.user.repository.UserRepository;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

@Service
public class EmailLoginService {
	private final UserRepository users;
	private final PasswordEncoder passwords;
	private final AuthService authService;
	private final String missingPasswordHash;

	public EmailLoginService(UserRepository users, PasswordEncoder passwords, AuthService authService) {
		this.users = users;
		this.passwords = passwords;
		this.authService = authService;
		this.missingPasswordHash = passwords.encode("unused-password-for-login-comparison");
	}

	@Transactional
	public TokenResponse login(String email, String password, String userAgent) {
		User user = users.findByEmail(email.toLowerCase(Locale.ROOT)).orElse(null);
		String passwordHash = user == null ? null : user.getPasswordHash();
		boolean matches = passwords.matches(password, passwordHash == null ? missingPasswordHash : passwordHash);
		if (passwordHash == null || !matches) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		user.recordLogin(Instant.now());
		return authService.startSession(user, userAgent);
	}
}
