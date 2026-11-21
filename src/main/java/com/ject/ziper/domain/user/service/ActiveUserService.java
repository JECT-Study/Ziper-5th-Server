package com.ject.ziper.domain.user.service;

import org.springframework.stereotype.Service;

import com.ject.ziper.domain.user.entity.UserStatus;
import com.ject.ziper.domain.user.repository.UserRepository;
import com.ject.ziper.global.security.ActiveUserChecker;

@Service
public class ActiveUserService implements ActiveUserChecker {
	private final UserRepository users;

	public ActiveUserService(UserRepository users) {
		this.users = users;
	}

	@Override
	public boolean isActive(long userId) {
		return users.existsByIdAndStatus(userId, UserStatus.ACTIVE);
	}
}
