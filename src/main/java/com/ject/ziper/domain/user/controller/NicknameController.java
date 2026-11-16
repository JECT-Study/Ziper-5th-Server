package com.ject.ziper.domain.user.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ject.ziper.domain.user.dto.NicknameAvailabilityResponse;
import com.ject.ziper.domain.user.service.NicknameService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@SecurityRequirements
@Tag(name = "회원")
public class NicknameController {
	private final NicknameService nicknames;

	public NicknameController(NicknameService nicknames) {
		this.nicknames = nicknames;
	}

	@GetMapping("/api/v1/users/nickname/availability")
	@Operation(summary = "닉네임 중복 및 유효성 검사", description = "한글·영문·숫자 2~10자 규칙과 중복 여부를 검사합니다.")
	public NicknameAvailabilityResponse checkAvailability(
			@Parameter(description = "검사할 닉네임 (2~10자 한글·영문·숫자)", example = "집퍼유저1") @RequestParam(required = false) String nickname) {
		nicknames.checkAvailable(nickname);
		return new NicknameAvailabilityResponse(true);
	}
}
