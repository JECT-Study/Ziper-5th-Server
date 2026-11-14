package com.ject.ziper.domain.user.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.user.dto.NicknameAvailabilityResponse;
import com.ject.ziper.domain.user.service.NicknameService;

@RestController
public class NicknameController {
	private final NicknameService nicknames;

	public NicknameController(NicknameService nicknames) {
		this.nicknames = nicknames;
	}

	@GetMapping("/api/v1/users/nickname/availability")
	public NicknameAvailabilityResponse checkAvailability(@RequestParam(required = false) String nickname) {
		nicknames.checkAvailable(nickname);
		return new NicknameAvailabilityResponse(true);
	}
}
