package com.ject.ziper.domain.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.auth.dto.AgreementStatus;
import com.ject.ziper.domain.auth.dto.OAuthLoginResponse;
import com.ject.ziper.domain.auth.dto.TokenResponse;
import com.ject.ziper.domain.auth.entity.Session;
import com.ject.ziper.domain.auth.repository.OAuthSignupTicketRepository;
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
	private static final Duration SIGNUP_WINDOW = Duration.ofMinutes(10);

	private final OAuthClientComposite oauthClientComposite;
	private final OAuthUserRepository oauthUserRepository;
	private final OAuthSignupTicketRepository signupTickets;
	private final UserRepository userRepository;
	private final ProductBoardRepository productBoardRepository;
	private final SessionRepository sessionRepository;
	private final NicknameGenerator nicknameGenerator;
	private final JwtTokenProvider jwtTokenProvider;
	private final AdvisoryLockRepository locks;
	private final Clock clock;

	public AuthService(OAuthClientComposite oauthClientComposite, OAuthUserRepository oauthUserRepository,
			OAuthSignupTicketRepository signupTickets, UserRepository userRepository,
			ProductBoardRepository productBoardRepository, SessionRepository sessionRepository,
			NicknameGenerator nicknameGenerator, JwtTokenProvider jwtTokenProvider, AdvisoryLockRepository locks) {
		this.oauthClientComposite = oauthClientComposite;
		this.oauthUserRepository = oauthUserRepository;
		this.signupTickets = signupTickets;
		this.userRepository = userRepository;
		this.productBoardRepository = productBoardRepository;
		this.sessionRepository = sessionRepository;
		this.nicknameGenerator = nicknameGenerator;
		this.jwtTokenProvider = jwtTokenProvider;
		this.locks = locks;
		this.clock = Clock.systemUTC();
	}

	@Transactional
	public OAuthLoginResponse loginWithOAuth(String providerName, String token, String userAgent) {
		OAuthProvider provider = OAuthProvider.from(providerName);
		OAuthUserPrincipal principal = oauthClientComposite.authenticate(provider, token);
		return completeLogin(principal, userAgent);
	}

	@Transactional
	public OAuthLoginResponse loginWithOAuthCode(String providerName, String code, String redirectUri, String state,
			String userAgent) {
		OAuthProvider provider = OAuthProvider.from(providerName);
		OAuthUserPrincipal principal = oauthClientComposite.authenticateWithCode(provider, code, redirectUri, state);
		return completeLogin(principal, userAgent);
	}

	private OAuthLoginResponse completeLogin(OAuthUserPrincipal principal, String userAgent) {
		Optional<OAuthUser> existingOAuth = lockedOAuthUser(principal);
		Instant now = clock.instant();
		if (existingOAuth.isPresent()) {
			User user = userRepository.findByOauthConnectId(existingOAuth.get().getId())
					.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
			if (user.getStatus() != UserStatus.ACTIVE) {
				throw new BusinessException(ErrorCode.FORBIDDEN);
			}
			user.recordLogin(now);
			return OAuthLoginResponse.login(startSession(user, userAgent), false);
		}
		String email = principal.email() == null ? null : principal.email().toLowerCase(Locale.ROOT);
		checkAvailableEmail(email);
		UUID signupToken = UUID.randomUUID();
		signupTickets.deleteExpired(now);
		signupTickets.insert(jwtTokenProvider.hashToken(signupToken.toString()),
				new OAuthUserPrincipal(principal.provider(), principal.providerId(), email), now.plus(SIGNUP_WINDOW));
		return OAuthLoginResponse.signupRequired(signupToken);
	}

	@Transactional
	public OAuthLoginResponse signupWithOAuth(UUID signupToken, boolean marketingAgreed, String userAgent) {
		OAuthUserPrincipal principal = signupTickets
				.consume(jwtTokenProvider.hashToken(signupToken.toString()), clock.instant())
				.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		if (lockedOAuthUser(principal).isPresent()) {
			throw new BusinessException(ErrorCode.CONFLICT);
		}
		if (principal.email() != null) {
			locks.lock("email:" + principal.email());
			checkAvailableEmail(principal.email());
		}
		String nickname = nicknameGenerator.generateUniqueNickname();
		OAuthUser oauthUser = oauthUserRepository.save(OAuthUser.create(principal.provider(), principal.providerId()));
		User user = userRepository.save(
				User.createOAuthUser(principal.email(), nickname, oauthUser.getId(), marketingAgreed, clock.instant()));
		productBoardRepository.save(ProductBoard.createDefault(user.getId()));
		return OAuthLoginResponse.login(startSession(user, userAgent), true);
	}

	private Optional<OAuthUser> lockedOAuthUser(OAuthUserPrincipal principal) {
		locks.lock("oauth:" + principal.provider().name() + ":" + principal.providerId());
		return oauthUserRepository.findByProviderAndProviderId(principal.provider(), principal.providerId());
	}

	private void checkAvailableEmail(String email) {
		if (email != null && userRepository.existsByEmailIgnoreCase(email)) {
			throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
		}
	}

	@Transactional
	public TokenResponse startSession(User user, String userAgent) {
		Long userId = user.getId();
		String accessToken = jwtTokenProvider.issueAccessToken(userId);
		String refreshToken = jwtTokenProvider.issueRefreshToken(userId);
		sessionRepository.save(Session.create(userId, userAgent, jwtTokenProvider.hashToken(refreshToken)));
		return new TokenResponse(accessToken, refreshToken, AgreementStatus.from(user));
	}

	@Transactional
	public TokenResponse reissue(String refreshToken, String userAgent) {
		Session session = lockedSession(refreshToken, false);
		Long userId = session.getUserId();
		User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		if (user.getStatus() != UserStatus.ACTIVE) {
			throw new BusinessException(ErrorCode.FORBIDDEN);
		}
		String newAccessToken = jwtTokenProvider.issueAccessToken(userId);
		String newRefreshToken = jwtTokenProvider.issueRefreshToken(userId);
		String newTokenHash = jwtTokenProvider.hashToken(newRefreshToken);
		session.rotate(newTokenHash, userAgent);
		return new TokenResponse(newAccessToken, newRefreshToken, AgreementStatus.from(user));
	}

	@Transactional
	public void logout(String refreshToken) {
		sessionRepository.delete(lockedSession(refreshToken, true));
	}

	private Session lockedSession(String refreshToken, boolean allowPreviousToken) {
		Long userId = jwtTokenProvider.parseRefreshTokenUserId(refreshToken);
		String currentHash = jwtTokenProvider.hashToken(refreshToken);
		locks.lock("refresh:" + currentHash);
		Optional<Session> found = allowPreviousToken
				? sessionRepository.lockByCurrentOrPreviousTokenHash(currentHash)
				: sessionRepository.lockByTokenHash(currentHash);
		Session session = found.orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
		if (!session.getUserId().equals(userId)) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		return session;
	}
}
