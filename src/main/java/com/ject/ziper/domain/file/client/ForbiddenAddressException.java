package com.ject.ziper.domain.file.client;

import java.net.UnknownHostException;

/**
 * Raised from the connection-time DNS resolution when any resolved address is
 * non-public. It is a checked {@link UnknownHostException} so the HTTP client
 * aborts the connect attempt instead of dialing the address.
 */
public class ForbiddenAddressException extends UnknownHostException {
	private static final long serialVersionUID = 1L;

	public ForbiddenAddressException(String host) {
		super("Host resolves to a non-public address: " + host);
	}
}
