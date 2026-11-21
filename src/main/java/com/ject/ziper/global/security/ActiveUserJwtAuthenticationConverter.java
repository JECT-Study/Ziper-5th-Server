package com.ject.ziper.global.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * 서명·만료·클레임 검증을 통과한 JWT의 회원이 존재하고 활성인지 확인한다. 없거나 비활성이면 인증 실패로 처리해 기존 JWT 401 응답
 * 경로를 재사용한다.
 */
public class ActiveUserJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
	private final ActiveUserChecker checker;
	private final JwtAuthenticationConverter delegate = new JwtAuthenticationConverter();

	public ActiveUserJwtAuthenticationConverter(ActiveUserChecker checker) {
		this.checker = checker;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		Long userId = parseUserId(jwt.getSubject());
		if (userId == null || !checker.isActive(userId)) {
			throw new InvalidBearerTokenException("Inactive or unknown user");
		}
		return delegate.convert(jwt);
	}

	private static Long parseUserId(String subject) {
		if (subject == null || subject.isEmpty() || subject.length() > 18) {
			return null;
		}
		for (int i = 0; i < subject.length(); i++) {
			if (subject.charAt(i) < '0' || subject.charAt(i) > '9') {
				return null;
			}
		}
		return Long.parseLong(subject);
	}
}
