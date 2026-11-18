package com.ject.ziper.domain.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.auth.dto.OAuthLoginResponse;
import com.ject.ziper.domain.auth.dto.TokenResponse;
import com.ject.ziper.domain.auth.entity.Session;
import com.ject.ziper.domain.auth.repository.SessionRepository;
import com.ject.ziper.domain.board.entity.ProductBoard;
import com.ject.ziper.domain.board.repository.ProductBoardRepository;
import com.ject.ziper.domain.user.entity.OAuthUser;
import com.ject.ziper.domain.user.entity.User;
import com.ject.ziper.domain.user.entity.UserStatus;
import com.ject.ziper.domain.user.repository.OAuthUserRepository;
import com.ject.ziper.domain.user.repository.UserRepository;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.ject.ziper.global.persistence.AdvisoryLockRepository;
import com.ject.ziper.global.security.JwtTokenProvider;
import com.ject.ziper.global.security.oauth.OAuthClientComposite;
import com.ject.ziper.global.security.oauth.OAuthProvider;
import com.ject.ziper.global.security.oauth.OAuthUserPrincipal;

@Service
public class AuthService {
	private final OAuthClientComposite oauthClientComposite;
	private final OAuthUserRepository oauthUserRepository;
	private final UserRepository userRepository;
	private final ProductBoardRepository productBoardRepository;
	private final SessionRepository sessionRepository;
	private final NicknameGenerator nicknameGenerator;
	private final JwtTokenProvider jwtTokenProvider;
	private final AdvisoryLockRepository locks;
	private final Clock clock;

	@Autowired
	public AuthService(OAuthClientComposite oauthClientComposite, OAuthUserRepository oauthUserRepository,
			UserRepository userRepository, ProductBoardRepository productBoardRepository,
			SessionRepository sessionRepository, NicknameGenerator nicknameGenerator, JwtTokenProvider jwtTokenProvider,
			AdvisoryLockRepository locks) {
		this(oauthClientComposite, oauthUserRepository, userRepository, productBoardRepository, sessionRepository,
				nicknameGenerator, jwtTokenProvider, locks, Clock.systemUTC());
	}

	public AuthService(OAuthClientComposite oauthClientComposite, OAuthUserRepository oauthUserRepository,
			UserRepository userRepository, ProductBoardRepository productBoardRepository,
			SessionRepository sessionRepository, NicknameGenerator nicknameGenerator, JwtTokenProvider jwtTokenProvider,
			AdvisoryLockRepository locks, Clock clock) {
		this.oauthClientComposite = oauthClientComposite;
		this.oauthUserRepository = oauthUserRepository;
		this.userRepository = userRepository;
		this.productBoardRepository = productBoardRepository;
		this.sessionRepository = sessionRepository;
		this.nicknameGenerator = nicknameGenerator;
		this.jwtTokenProvider = jwtTokenProvider;
		this.locks = locks;
		this.clock = clock;
	}

	@Transactional
	public OAuthLoginResponse loginWithOAuth(String providerName, String token, String userAgent) {
		OAuthProvider provider = OAuthProvider.from(providerName);
		OAuthUserPrincipal principal = oauthClientComposite.authenticate(provider, token);
		return completeLogin(provider, principal, userAgent);
	}

	@Transactional
	public OAuthLoginResponse loginWithOAuthCode(String providerName, String code, String redirectUri, String state,
			String userAgent) {
		OAuthProvider provider = OAuthProvider.from(providerName);
		OAuthUserPrincipal principal = oauthClientComposite.authenticateWithCode(provider, code, redirectUri, state);
		return completeLogin(provider, principal, userAgent);
	}

	private OAuthLoginResponse completeLogin(OAuthProvider provider, OAuthUserPrincipal principal, String userAgent) {
		// 같은 소셜 계정의 동시 첫 로그인을 직렬화해, 뒤 요청이 고유 제약 위반 대신 먼저 가입된 회원으로 로그인하게 한다.
		locks.lock("oauth:" + provider.name() + ":" + principal.providerId());
		Optional<OAuthUser> existingOAuth = oauthUserRepository.findByProviderAndProviderId(provider,
				principal.providerId());
		Instant now = clock.instant();
		User user;
		boolean isNewUser;
		if (existingOAuth.isPresent()) {
			user = userRepository.findByOauthConnectId(existingOAuth.get().getId())
					.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
			if (user.getStatus() != UserStatus.ACTIVE) {
				throw new BusinessException(ErrorCode.FORBIDDEN);
			}
			user.recordLogin(now);
			isNewUser = false;
		} else {
			String email = normalizeEmail(principal.email());
			if (email != null) {
				// 이메일 가입·인증과 같은 키로 잠가 같은 이메일의 동시 가입을 막는다. 이메일 계정과 자동 연결하지 않는다.
				locks.lock("email:" + email);
				if (userRepository.existsByEmailIgnoreCase(email)) {
					throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
				}
			}
			String nickname = nicknameGenerator.generateUniqueNickname();
			OAuthUser savedOAuth = oauthUserRepository.save(OAuthUser.create(provider, principal.providerId()));
			user = userRepository.save(User.createOAuthUser(email, nickname, savedOAuth.getId(), now));
			productBoardRepository.save(ProductBoard.createDefault(user.getId()));
			isNewUser = true;
		}
		TokenResponse tokens = startSession(user.getId(), userAgent);
		return new OAuthLoginResponse(tokens.accessToken(), tokens.refreshToken(), isNewUser);
	}

	private static String normalizeEmail(String email) {
		return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
	}

	@Transactional
	public TokenResponse startSession(Long userId, String userAgent) {
		String accessToken = jwtTokenProvider.issueAccessToken(userId);
		String refreshToken = jwtTokenProvider.issueRefreshToken(userId);
		sessionRepository.save(Session.create(userId, userAgent, jwtTokenProvider.hashToken(refreshToken)));
		return new TokenResponse(accessToken, refreshToken);
	}

	@Transactional
	public TokenResponse reissue(String refreshToken, String userAgent) {
		Long userId = jwtTokenProvider.parseRefreshTokenUserId(refreshToken);
		String currentHash = jwtTokenProvider.hashToken(refreshToken);
		Session session = sessionRepository.findByTokenHash(currentHash)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		if (!session.getUserId().equals(userId)) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		String newAccessToken = jwtTokenProvider.issueAccessToken(userId);
		String newRefreshToken = jwtTokenProvider.issueRefreshToken(userId);
		String newTokenHash = jwtTokenProvider.hashToken(newRefreshToken);
		session.rotate(newTokenHash, userAgent);
		return new TokenResponse(newAccessToken, newRefreshToken);
	}

	@Transactional
	public void logout(String refreshToken) {
		Long userId = jwtTokenProvider.parseRefreshTokenUserId(refreshToken);
		String currentHash = jwtTokenProvider.hashToken(refreshToken);
		Session session = sessionRepository.findByTokenHash(currentHash)
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		if (!session.getUserId().equals(userId)) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		sessionRepository.delete(session);
	}
}
