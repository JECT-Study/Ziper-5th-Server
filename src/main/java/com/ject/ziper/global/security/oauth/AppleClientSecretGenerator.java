package com.ject.ziper.global.security.oauth;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ject.ziper.global.error.BusinessException;
import com.ject.ziper.global.error.ErrorCode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Generates short-lived ES256 client_secret JWT assertions for Apple token
 * exchange.
 */
@Component
public class AppleClientSecretGenerator {
	private static final long CLIENT_SECRET_TTL_SECONDS = 300L;
	private static final String PEM_HEADER = "-----BEGIN PRIVATE KEY-----";
	private static final String PEM_FOOTER = "-----END PRIVATE KEY-----";

	private final Clock clock;

	@Autowired
	public AppleClientSecretGenerator() {
		this(Clock.systemUTC());
	}

	public AppleClientSecretGenerator(Clock clock) {
		this.clock = clock;
	}

	public String generate(String issuer, String clientId, String teamId, String keyId, String privateKeyPem) {
		if (isBlank(issuer) || isBlank(clientId) || isBlank(teamId) || isBlank(keyId) || isBlank(privateKeyPem)) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
		try {
			ECPrivateKey privateKey = parsePrivateKey(privateKeyPem);
			Instant now = clock.instant();
			JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(keyId.trim()).build();
			JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(teamId.trim()).subject(clientId.trim())
					.audience(issuer.trim()).issueTime(Date.from(now))
					.expirationTime(Date.from(now.plusSeconds(CLIENT_SECRET_TTL_SECONDS))).build();
			SignedJWT signedJwt = new SignedJWT(header, claims);
			signedJwt.sign(new ECDSASigner(privateKey));
			return signedJwt.serialize();
		} catch (GeneralSecurityException | JOSEException | IllegalArgumentException exception) {
			throw new BusinessException(ErrorCode.UNAUTHORIZED);
		}
	}

	private ECPrivateKey parsePrivateKey(String privateKeyPem) throws GeneralSecurityException {
		String normalized = privateKeyPem.replace("\\n", "\n").replace(PEM_HEADER, "").replace(PEM_FOOTER, "")
				.replaceAll("\\s+", "");
		byte[] encoded = Base64.getDecoder().decode(normalized);
		KeyFactory keyFactory = KeyFactory.getInstance("EC");
		return (ECPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
