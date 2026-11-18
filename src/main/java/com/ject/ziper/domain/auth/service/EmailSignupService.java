package com.ject.ziper.domain.auth.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.auth.dto.TokenResponse;
import com.ject.ziper.domain.auth.repository.EmailVerificationRepository;
import com.ject.ziper.domain.auth.repository.EmailVerificationRepository.AuthCode;
import com.ject.ziper.domain.board.entity.ProductBoard;
import com.ject.ziper.domain.board.repository.ProductBoardRepository;
import com.ject.ziper.domain.user.entity.User;
import com.ject.ziper.domain.user.repository.UserRepository;
import com.ject.ziper.domain.user.service.NicknameService;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.persistence.AdvisoryLockRepository;

@Service
@ConditionalOnProperty(prefix = "app.mail", name = "enabled", havingValue = "true")
public class EmailSignupService {
	// 인증을 마친 뒤 이 시간 안에 가입해야 한다. 지나면 인증부터 다시 시작한다.
	private static final Duration SIGNUP_WINDOW = Duration.ofMinutes(30);
	// 영문·숫자·특수문자를 각각 1자 이상 포함한 공백 없는 ASCII 출력 문자 8~64자. BCrypt 72바이트 입력 한도 안에 둔다.
	private static final Pattern PASSWORD = Pattern
			.compile("(?=.*[A-Za-z])(?=.*[0-9])(?=.*[!-/:-@\\[-`{-~])[!-~]{8,64}");

	private final EmailVerificationRepository verifications;
	private final UserRepository users;
	private final ProductBoardRepository boards;
	private final NicknameService nicknames;
	private final AuthService authService;
	private final PasswordEncoder passwordEncoder;
	private final AdvisoryLockRepository locks;

	public EmailSignupService(EmailVerificationRepository verifications, UserRepository users,
			ProductBoardRepository boards, NicknameService nicknames, AuthService authService,
			PasswordEncoder passwordEncoder, AdvisoryLockRepository locks) {
		this.verifications = verifications;
		this.users = users;
		this.boards = boards;
		this.nicknames = nicknames;
		this.authService = authService;
		this.passwordEncoder = passwordEncoder;
		this.locks = locks;
	}

	@Transactional
	public TokenResponse signup(UUID flowId, String password, String nickname, boolean marketingAgreed,
			String userAgent) {
		if (!PASSWORD.matcher(password).matches()) {
			throw new BusinessException(ErrorCode.INVALID_PASSWORD);
		}
		locks.lock("flow:" + flowId);
		AuthCode latest = verifications.latestByFlow(flowId);
		Instant now = Instant.now();
		if (latest == null || !latest.verified() || latest.consumed()
				|| !latest.verifiedAt().plus(SIGNUP_WINDOW).isAfter(now)) {
			throw new BusinessException(ErrorCode.EMAIL_FLOW_INVALID);
		}
		locks.lock("email:" + latest.email());
		locks.lock("nickname:" + nickname.toLowerCase(Locale.ROOT));
		if (users.existsByEmailIgnoreCase(latest.email())) {
			throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
		}
		nicknames.checkAvailable(nickname);
		User user = users.save(
				User.createEmailUser(latest.email(), passwordEncoder.encode(password), nickname, marketingAgreed, now));
		boards.save(ProductBoard.createDefault(user.getId()));
		verifications.markConsumed(latest.id(), now);
		return authService.startSession(user.getId(), userAgent);
	}
}
