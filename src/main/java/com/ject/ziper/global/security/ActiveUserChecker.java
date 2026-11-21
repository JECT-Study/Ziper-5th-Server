package com.ject.ziper.global.security;

/**
 * 유효한 Access Token의 회원이 지금도 활성 상태인지 확인하는 인증 경계 포트다. 구현은 요청마다 저장소를 조회하며 프로세스
 * 메모리에 상태를 캐시하지 않는다.
 */
public interface ActiveUserChecker {
	boolean isActive(long userId);
}
