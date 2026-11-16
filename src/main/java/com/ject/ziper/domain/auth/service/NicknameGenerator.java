package com.ject.ziper.domain.auth.service;

import java.security.SecureRandom;
import java.util.function.IntSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ject.ziper.domain.user.repository.UserRepository;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

@Component
public class NicknameGenerator {
	private static final String PREFIX = "Ziper";
	private static final int BOUND = 100_000;
	private static final int MAX_ATTEMPTS = 50;

	private final UserRepository userRepository;
	private final IntSupplier numberSupplier;

	@Autowired
	public NicknameGenerator(UserRepository userRepository) {
		SecureRandom random = new SecureRandom();
		this.userRepository = userRepository;
		this.numberSupplier = () -> random.nextInt(BOUND);
	}

	public NicknameGenerator(UserRepository userRepository, IntSupplier numberSupplier) {
		this.userRepository = userRepository;
		this.numberSupplier = numberSupplier;
	}

	public String generateUniqueNickname() {
		for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
			int value = Math.floorMod(numberSupplier.getAsInt(), BOUND);
			String candidate = "%s%05d".formatted(PREFIX, value);
			if (!userRepository.existsByNickname(candidate)) {
				return candidate;
			}
		}
		throw new BusinessException(ErrorCode.CONFLICT);
	}
}
