package com.ject.ziper.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.ject.ziper.domain.user.repository.UserRepository;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

class NicknameGeneratorTest {
	@Test
	void 첫후보가이미존재하는닉네임_닉네임을생성하면_중복을피해Ziper와5자리숫자닉네임을반환한다() {
		UserRepository repository = mock(UserRepository.class);
		given(repository.existsByNickname("Ziper00007")).willReturn(true);
		given(repository.existsByNickname("Ziper00042")).willReturn(false);
		AtomicInteger sequence = new AtomicInteger(0);
		int[] numbers = {7, 42};
		NicknameGenerator generator = new NicknameGenerator(repository, () -> numbers[sequence.getAndIncrement()]);

		String nickname = generator.generateUniqueNickname();

		assertThat(nickname).isEqualTo("Ziper00042").matches("^Ziper\\d{5}$").hasSize(10);
	}

	@Test
	void 모든후보가중복되는상태_닉네임을생성하면_CONFLICT예외를던진다() {
		UserRepository repository = mock(UserRepository.class);
		given(repository.existsByNickname(anyString())).willReturn(true);
		NicknameGenerator generator = new NicknameGenerator(repository, () -> 12345);

		assertThatThrownBy(generator::generateUniqueNickname).isInstanceOf(BusinessException.class)
				.extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
	}
}
