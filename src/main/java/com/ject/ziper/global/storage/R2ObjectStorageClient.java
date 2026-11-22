package com.ject.ziper.global.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class R2ObjectStorageClient implements ObjectStorageClient {
	private static final String ALGORITHM = "AWS4-HMAC-SHA256";
	private static final String REGION = "auto";
	private static final String SERVICE = "s3";
	private static final String TERMINATOR = "aws4_request";
	private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
	private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
	private static final long MAX_PRESIGN_SECONDS = 604_800L;
	private static final DateTimeFormatter AMZ_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
			.withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter DATE_STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
			.withZone(ZoneOffset.UTC);

	private final StorageProperties properties;
	private final RestClient restClient;
	private final Clock clock;

	public R2ObjectStorageClient(StorageProperties properties, RestClient.Builder restClientBuilder) {
		this(properties, restClientBuilder, Clock.systemUTC());
	}

	R2ObjectStorageClient(StorageProperties properties, RestClient.Builder restClientBuilder, Clock clock) {
		this.properties = properties;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.r2ConnectTimeoutMs());
		requestFactory.setReadTimeout(properties.r2ReadTimeoutMs());
		this.restClient = restClientBuilder.requestFactory(requestFactory).build();
		this.clock = clock;
	}

	@Override
	public void putObject(String storageKey, byte[] content, String contentType) {
		URI baseUri = ensureConfigured();
		if (content == null) {
			throw new IllegalArgumentException("content must not be null");
		}
		MediaType parsedContentType = MediaType.parseMediaType(
				contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
		String canonicalPath = canonicalObjectPath(storageKey);
		Instant now = clock.instant();
		String amzDate = AMZ_DATE_FORMAT.format(now);
		String dateStamp = DATE_STAMP_FORMAT.format(now);
		String payloadSha256 = sha256Hex(content);
		String hostHeader = hostHeader(baseUri);

		Map<String, String> signedHeadersMap = new TreeMap<>();
		signedHeadersMap.put("content-type", parsedContentType.toString());
		signedHeadersMap.put("host", hostHeader);
		signedHeadersMap.put("x-amz-content-sha256", payloadSha256);
		signedHeadersMap.put("x-amz-date", amzDate);

		String authorization = buildAuthorizationHeader("PUT", canonicalPath, "", signedHeadersMap, payloadSha256,
				amzDate, dateStamp);
		URI uri = URI.create(properties.r2Endpoint() + canonicalPath);

		try {
			restClient.put().uri(uri).header(HttpHeaders.AUTHORIZATION, authorization)
					.header("x-amz-content-sha256", payloadSha256).header("x-amz-date", amzDate)
					.contentType(parsedContentType).body(content).retrieve().toBodilessEntity();
		} catch (RestClientException exception) {
			throw new IllegalStateException("Failed to upload object to Cloudflare R2", exception);
		}
	}

	@Override
	public String createSignedUrl(String storageKey, Duration expiresIn) {
		URI baseUri = ensureConfigured();
		String canonicalPath = canonicalObjectPath(storageKey);
		long rawSeconds = expiresIn == null ? properties.signedUrlExpirationSeconds() : expiresIn.toSeconds();
		long seconds = Math.max(1L, Math.min(MAX_PRESIGN_SECONDS, rawSeconds));

		Instant now = clock.instant();
		String amzDate = AMZ_DATE_FORMAT.format(now);
		String dateStamp = DATE_STAMP_FORMAT.format(now);
		String credentialScope = credentialScope(dateStamp);
		String hostHeader = hostHeader(baseUri);

		Map<String, String> queryParams = new TreeMap<>();
		queryParams.put("X-Amz-Algorithm", ALGORITHM);
		queryParams.put("X-Amz-Credential", properties.r2AccessKeyId() + "/" + credentialScope);
		queryParams.put("X-Amz-Date", amzDate);
		queryParams.put("X-Amz-Expires", Long.toString(seconds));
		queryParams.put("X-Amz-SignedHeaders", "host");

		String canonicalQuery = queryParams.entrySet().stream()
				.map(entry -> rfc3986Encode(entry.getKey()) + "=" + rfc3986Encode(entry.getValue()))
				.collect(Collectors.joining("&"));
		String canonicalRequest = "GET\n" + canonicalPath + "\n" + canonicalQuery + "\nhost:" + hostHeader
				+ "\n\nhost\n" + UNSIGNED_PAYLOAD;
		String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + credentialScope + "\n"
				+ sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
		String signature = signHex(dateStamp, stringToSign);

		return properties.r2Endpoint() + canonicalPath + "?" + canonicalQuery + "&X-Amz-Signature=" + signature;
	}

	@Override
	public Map<String, String> createSignedUrls(Collection<String> storageKeys, Duration expiresIn) {
		if (storageKeys == null || storageKeys.isEmpty()) {
			return Map.of();
		}
		ensureConfigured();
		Map<String, String> result = new LinkedHashMap<>();
		for (String storageKey : storageKeys) {
			if (storageKey == null || storageKey.isBlank()) {
				continue;
			}
			result.putIfAbsent(storageKey, createSignedUrl(storageKey, expiresIn));
		}
		return result;
	}

	@Override
	public void deleteObject(String storageKey) {
		URI baseUri = ensureConfigured();
		String canonicalPath = canonicalObjectPath(storageKey);
		Instant now = clock.instant();
		String amzDate = AMZ_DATE_FORMAT.format(now);
		String dateStamp = DATE_STAMP_FORMAT.format(now);
		String hostHeader = hostHeader(baseUri);

		Map<String, String> signedHeadersMap = new TreeMap<>();
		signedHeadersMap.put("host", hostHeader);
		signedHeadersMap.put("x-amz-content-sha256", EMPTY_SHA256);
		signedHeadersMap.put("x-amz-date", amzDate);

		String authorization = buildAuthorizationHeader("DELETE", canonicalPath, "", signedHeadersMap, EMPTY_SHA256,
				amzDate, dateStamp);
		URI uri = URI.create(properties.r2Endpoint() + canonicalPath);

		try {
			restClient.delete().uri(uri).header(HttpHeaders.AUTHORIZATION, authorization)
					.header("x-amz-content-sha256", EMPTY_SHA256).header("x-amz-date", amzDate).retrieve()
					.toBodilessEntity();
		} catch (HttpClientErrorException exception) {
			if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
				return;
			}
			throw new IllegalStateException("Failed to delete object from Cloudflare R2", exception);
		} catch (RestClientException exception) {
			throw new IllegalStateException("Failed to delete object from Cloudflare R2", exception);
		}
	}

	private URI ensureConfigured() {
		if (properties.r2Endpoint().isBlank() || properties.r2AccessKeyId().isBlank()
				|| properties.r2SecretAccessKey().isBlank() || properties.bucket().isBlank()) {
			throw new IllegalStateException(
					"Cloudflare R2 requires app.storage.r2-endpoint, r2-access-key-id, r2-secret-access-key, and bucket");
		}
		URI baseUri = URI.create(properties.r2Endpoint());
		if (!("https".equalsIgnoreCase(baseUri.getScheme()) || "http".equalsIgnoreCase(baseUri.getScheme()))
				|| baseUri.getHost() == null) {
			throw new IllegalStateException("app.storage.r2-endpoint must be a valid HTTP(S) URI");
		}
		return baseUri;
	}

	private String canonicalObjectPath(String storageKey) {
		if (storageKey == null || storageKey.isBlank()) {
			throw new IllegalArgumentException("storageKey must not be blank");
		}
		String encodedBucket = rfc3986Encode(properties.bucket());
		String encodedKey = Arrays.stream(storageKey.split("/")).filter(segment -> !segment.isEmpty())
				.map(R2ObjectStorageClient::rfc3986Encode).collect(Collectors.joining("/"));
		if (encodedKey.isEmpty()) {
			throw new IllegalArgumentException("storageKey must not be blank");
		}
		return "/" + encodedBucket + "/" + encodedKey;
	}

	private String buildAuthorizationHeader(String method, String canonicalPath, String canonicalQuery,
			Map<String, String> sortedHeaders, String payloadSha256, String amzDate, String dateStamp) {
		String canonicalHeaders = sortedHeaders.entrySet().stream()
				.map(entry -> entry.getKey() + ":" + entry.getValue().trim() + "\n").collect(Collectors.joining());
		String signedHeaders = String.join(";", sortedHeaders.keySet());
		String canonicalRequest = method + "\n" + canonicalPath + "\n" + canonicalQuery + "\n" + canonicalHeaders + "\n"
				+ signedHeaders + "\n" + payloadSha256;
		String scope = credentialScope(dateStamp);
		String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
				+ sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
		String signature = signHex(dateStamp, stringToSign);
		return ALGORITHM + " Credential=" + properties.r2AccessKeyId() + "/" + scope + ", SignedHeaders="
				+ signedHeaders + ", Signature=" + signature;
	}

	private String signHex(String dateStamp, String stringToSign) {
		byte[] kDate = hmacSha256(("AWS4" + properties.r2SecretAccessKey()).getBytes(StandardCharsets.UTF_8),
				dateStamp);
		byte[] kRegion = hmacSha256(kDate, REGION);
		byte[] kService = hmacSha256(kRegion, SERVICE);
		byte[] kSigning = hmacSha256(kService, TERMINATOR);
		return toHex(hmacSha256(kSigning, stringToSign));
	}

	private static String credentialScope(String dateStamp) {
		return dateStamp + "/" + REGION + "/" + SERVICE + "/" + TERMINATOR;
	}

	private static String hostHeader(URI baseUri) {
		if (baseUri.getPort() > 0) {
			return baseUri.getHost() + ":" + baseUri.getPort();
		}
		return baseUri.getHost();
	}

	private static byte[] hmacSha256(byte[] key, String message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException | InvalidKeyException exception) {
			throw new IllegalStateException("Failed to compute AWS SigV4 HMAC-SHA256 signature", exception);
		}
	}

	private static String sha256Hex(byte[] bytes) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return toHex(digest.digest(bytes));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 algorithm unavailable", exception);
		}
	}

	private static String toHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			builder.append(Character.forDigit((b >> 4) & 0xF, 16));
			builder.append(Character.forDigit(b & 0xF, 16));
		}
		return builder.toString();
	}

	private static String rfc3986Encode(String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		StringBuilder encoded = new StringBuilder(bytes.length);
		for (byte b : bytes) {
			int unsigned = b & 0xFF;
			if ((unsigned >= 'A' && unsigned <= 'Z') || (unsigned >= 'a' && unsigned <= 'z')
					|| (unsigned >= '0' && unsigned <= '9') || unsigned == '-' || unsigned == '.' || unsigned == '_'
					|| unsigned == '~') {
				encoded.append((char) unsigned);
			} else {
				encoded.append('%');
				encoded.append(Character.toUpperCase(Character.forDigit((unsigned >> 4) & 0xF, 16)));
				encoded.append(Character.toUpperCase(Character.forDigit(unsigned & 0xF, 16)));
			}
		}
		return encoded.toString();
	}
}
