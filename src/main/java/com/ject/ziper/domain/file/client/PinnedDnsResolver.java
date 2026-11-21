package com.ject.ziper.domain.file.client;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.function.Predicate;

import org.apache.hc.client5.http.DnsResolver;

/**
 * Resolves a host, verifies that every returned address is public and returns
 * exactly that vetted set to the HTTP client. The client therefore connects
 * only to addresses that passed the check; there is no second, unchecked lookup
 * (DNS rebinding) between validation and connection. TLS host name verification
 * is left to the client defaults and still runs against the original host name.
 */
final class PinnedDnsResolver implements DnsResolver {
	private final ExternalImageFetcher.DnsResolver delegate;
	private final Predicate<InetAddress> forbidden;

	PinnedDnsResolver(ExternalImageFetcher.DnsResolver delegate, Predicate<InetAddress> forbidden) {
		this.delegate = delegate;
		this.forbidden = forbidden;
	}

	@Override
	public InetAddress[] resolve(String host) throws UnknownHostException {
		InetAddress[] addresses = delegate.resolve(host);
		if (addresses == null || addresses.length == 0) {
			throw new UnknownHostException(host);
		}
		for (InetAddress address : addresses) {
			if (forbidden.test(address)) {
				throw new ForbiddenAddressException(host);
			}
		}
		return addresses;
	}

	@Override
	public String resolveCanonicalHostname(String host) {
		return host;
	}
}
