package com.ject.ziper.domain.auth.dto;

import java.time.Instant;

import com.ject.ziper.domain.user.entity.User;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "기록된 약관 동의 여부와 UTC 시각. 필수 약관 기록이 없으면 해당 시각은 null입니다.")
public record AgreementStatus(boolean termsOfService, boolean privacyPolicy, boolean marketing, Instant termsAgreedAt,
		Instant privacyAgreedAt, Instant marketingAgreedAt) {

	public static AgreementStatus from(User user) {
		return new AgreementStatus(user.getTermsAgreedAt() != null, user.getPrivacyAgreedAt() != null,
				user.isMarketingAgreed(), user.getTermsAgreedAt(), user.getPrivacyAgreedAt(),
				user.getMarketingAgreedAt());
	}
}
