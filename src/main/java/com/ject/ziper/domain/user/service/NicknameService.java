package com.ject.ziper.domain.user.service;

import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ject.ziper.domain.user.repository.UserRepository;
import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;

@Service
public class NicknameService {
	// 한글 완성형(가~힣), 영문 대소문자, 숫자만 2~10자. 공백·특수문자·자모 단독은 허용하지 않는다.
	private static final Pattern NICKNAME = Pattern.compile("[\\uAC00-\\uD7A3A-Za-z0-9]{2,10}");

	private final UserRepository users;

	public NicknameService(UserRepository users) {
		this.users = users;
	}

	@Transactional(readOnly = true)
	public void checkAvailable(String nickname) {
		if (nickname == null || !NICKNAME.matcher(nickname).matches()) {
			throw new BusinessException(ErrorCode.INVALID_NICKNAME);
		}
		if (users.existsByNickname(nickname)) {
			throw new BusinessException(ErrorCode.DUPLICATE_NICKNAME);
		}
	}
}
