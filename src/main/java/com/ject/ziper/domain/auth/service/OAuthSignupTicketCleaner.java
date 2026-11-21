package com.ject.ziper.domain.auth.service;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ject.ziper.domain.auth.repository.OAuthSignupTicketRepository;

/**
 * 만료된 소셜 가입 티켓을 티켓 발급과 무관하게 정리한다. 삭제는 {@code FOR UPDATE SKIP LOCKED} 배치로 수행하므로
 * 여러 인스턴스가 동시에 실행해도 같은 행을 두고 대기하거나 충돌하지 않는다.
 */
@Component
public class OAuthSignupTicketCleaner {
	static final int BATCH_SIZE = OAuthSignupTicketRepository.DELETE_BATCH_SIZE;

	private final OAuthSignupTicketRepository signupTickets;
	private final Clock clock;

	public OAuthSignupTicketCleaner(OAuthSignupTicketRepository signupTickets) {
		this.signupTickets = signupTickets;
		this.clock = Clock.systemUTC();
	}

	@Scheduled(fixedDelay = 60000, initialDelay = 60000)
	public void deleteExpiredTickets() {
		int deleted;
		do {
			deleted = signupTickets.deleteExpired(clock.instant());
		} while (deleted >= BATCH_SIZE);
	}
}
