package com.ject.ziper.domain.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ject.ziper.domain.user.entity.OAuthUser;
import com.ject.ziper.global.security.oauth.OAuthProvider;

public interface OAuthUserRepository extends JpaRepository<OAuthUser, Long> {
	Optional<OAuthUser> findByProviderAndProviderId(OAuthProvider provider, String providerId);
}
