package com.ject.ziper.domain.auth.service;

import java.time.Clock;
import java.time.Instant;
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
	private final Clock clock;

	@Autowired
	public AuthService(OAuthClientComposite oauthClientComposite, OAuthUserRepository oauthUserRepository,
			UserRepository userRepository, ProductBoardRepository productBoardRepository,
			SessionRepository sessionRepository, NicknameGenerator nicknameGenerator,
			JwtTokenProvider jwtTokenProvider) {
		this(oauthClientComposite, oauthUserRepository, userRepository, productBoardRepository, sessionRepository,
				nicknameGenerator, jwtTokenProvider, Clock.systemUTC());
	}

	public AuthService(OAuthClientComposite oauthClientComposite, OAuthUserRepository oauthUserRepository,
			UserRepository userRepository, ProductBoardRepository productBoardRepository,
			SessionRepository sessionRepository, NicknameGenerator nicknameGenerator, JwtTokenProvider jwtTokenProvider,
			Clock clock) {
		this.oauthClientComposite = oauthClientComposite;
		this.oauthUserRepository = oauthUserRepository;
		this.userRepository = userRepository;
		this.productBoardRepository = productBoardRepository;
		this.sessionRepository = sessionRepository;
		this.nicknameGenerator = nicknameGenerator;
		this.jwtTokenProvider = jwtTokenProvider;
		this.clock = clock;
	}

	@Transactional
	public OAuthLoginResponse loginWithOAuth(String providerName, String token, String userAgent) {
		OAuthProvider provider = OAuthProvider.from(providerName);
		OAuthUserPrincipal principal = oauthClientComposite.authenticate(provider, token);
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
			if (principal.email() == null) {
				throw new BusinessException(ErrorCode.UNAUTHORIZED);
			}
			if (userRepository.existsByEmail(principal.email())) {
				throw new BusinessException(ErrorCode.CONFLICT);
			}
			String nickname = nicknameGenerator.generateUniqueNickname();
			OAuthUser savedOAuth = oauthUserRepository.save(OAuthUser.create(provider, principal.providerId()));
			user = userRepository.save(User.createOAuthUser(principal.email(), nickname, savedOAuth.getId(), now));
			productBoardRepository.save(ProductBoard.createDefault(user.getId()));
			isNewUser = true;
		}
		String accessToken = jwtTokenProvider.issueAccessToken(user.getId());
		String refreshToken = jwtTokenProvider.issueRefreshToken(user.getId());
		String tokenHash = jwtTokenProvider.hashToken(refreshToken);
		sessionRepository.save(Session.create(user.getId(), userAgent, tokenHash));
		return new OAuthLoginResponse(accessToken, refreshToken, isNewUser);
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
