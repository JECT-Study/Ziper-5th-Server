package com.ject.ziper.global.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class EmailCodeHmac {
	private final SecretKeySpec key;

	public EmailCodeHmac(String secretBase64) {
		byte[] secret = Base64.getDecoder().decode(secretBase64);
		if (secret.length < 32) {
			throw new IllegalArgumentException("Email code secret must be at least 32 bytes");
		}
		key = new SecretKeySpec(secret, "HmacSHA256");
	}

	public String hash(String value) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Email code HMAC is unavailable", exception);
		}
	}
}
