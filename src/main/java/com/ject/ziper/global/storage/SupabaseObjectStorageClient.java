package com.ject.ziper.global.storage;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.annotation.JsonProperty;

public class SupabaseObjectStorageClient implements ObjectStorageClient {
	private final StorageProperties properties;
	private final RestClient restClient;

	public SupabaseObjectStorageClient(StorageProperties properties, RestClient.Builder restClientBuilder) {
		this.properties = properties;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.supabaseConnectTimeoutMs());
		requestFactory.setReadTimeout(properties.supabaseReadTimeoutMs());
		this.restClient = restClientBuilder.requestFactory(requestFactory).build();
	}

	@Override
	public void putObject(String storageKey, byte[] content, String contentType) {
		ensureConfigured();
		URI uri = objectUri("/storage/v1/object/", storageKey);
		try {
			restClient.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearerHeader())
					.header("apikey", properties.supabaseServiceRoleKey()).header("x-upsert", "true")
					.contentType(MediaType.parseMediaType(contentType)).body(content).retrieve().toBodilessEntity();
		} catch (RestClientException exception) {
			throw new IllegalStateException("Failed to upload object to Supabase Storage", exception);
		}
	}

	@Override
	public String createSignedUrl(String storageKey, Duration expiresIn) {
		ensureConfigured();
		long seconds = expiresIn == null
				? properties.signedUrlExpirationSeconds()
				: Math.max(1L, expiresIn.toSeconds());
		URI uri = objectUri("/storage/v1/object/sign/", storageKey);
		SignedUrlResponse response;
		try {
			response = restClient.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearerHeader())
					.header("apikey", properties.supabaseServiceRoleKey()).contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("expiresIn", seconds)).retrieve().body(SignedUrlResponse.class);
		} catch (RestClientException exception) {
			throw new IllegalStateException("Failed to create signed URL from Supabase Storage", exception);
		}
		if (response == null || response.signedUrl() == null || response.signedUrl().isBlank()) {
			throw new IllegalStateException("Supabase Storage returned empty signed URL");
		}
		return resolveSignedUrl(response.signedUrl().trim());
	}

	@Override
	public Map<String, String> createSignedUrls(Collection<String> storageKeys, Duration expiresIn) {
		if (storageKeys == null || storageKeys.isEmpty()) {
			return Map.of();
		}
		ensureConfigured();
		long seconds = expiresIn == null
				? properties.signedUrlExpirationSeconds()
				: Math.max(1L, expiresIn.toSeconds());
		List<String> paths = List.copyOf(storageKeys);
		URI uri = URI
				.create(properties.supabaseUrl() + "/storage/v1/object/sign/" + encodeSegment(properties.bucket()));
		BatchSignedUrlItem[] items;
		try {
			items = restClient.post().uri(uri).header(HttpHeaders.AUTHORIZATION, bearerHeader())
					.header("apikey", properties.supabaseServiceRoleKey()).contentType(MediaType.APPLICATION_JSON)
					.body(Map.of("expiresIn", seconds, "paths", paths)).retrieve().body(BatchSignedUrlItem[].class);
		} catch (RestClientException exception) {
			throw new IllegalStateException("Failed to create signed URLs from Supabase Storage", exception);
		}
		if (items == null) {
			throw new IllegalStateException("Supabase Storage returned an empty batch signed URL response");
		}
		Map<String, String> result = new LinkedHashMap<>();
		for (BatchSignedUrlItem item : items) {
			// A per-path error (for example a missing object) is data: that key simply has
			// no URL.
			if (item == null || item.path() == null || item.error() != null || item.signedUrl() == null
					|| item.signedUrl().isBlank()) {
				continue;
			}
			result.put(item.path(), resolveSignedUrl(item.signedUrl().trim()));
		}
		return result;
	}

	@Override
	public void deleteObject(String storageKey) {
		ensureConfigured();
		URI uri = objectUri("/storage/v1/object/", storageKey);
		try {
			restClient.delete().uri(uri).header(HttpHeaders.AUTHORIZATION, bearerHeader())
					.header("apikey", properties.supabaseServiceRoleKey()).retrieve().toBodilessEntity();
		} catch (HttpClientErrorException exception) {
			if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
				return;
			}
			throw new IllegalStateException("Failed to delete object from Supabase Storage", exception);
		} catch (RestClientException exception) {
			throw new IllegalStateException("Failed to delete object from Supabase Storage", exception);
		}
	}

	private void ensureConfigured() {
		if (properties.supabaseUrl().isBlank() || properties.supabaseServiceRoleKey().isBlank()) {
			throw new IllegalStateException(
					"Supabase Storage requires app.storage.supabase-url and app.storage.supabase-service-role-key");
		}
		URI baseUri = URI.create(properties.supabaseUrl());
		if (!("https".equalsIgnoreCase(baseUri.getScheme()) || "http".equalsIgnoreCase(baseUri.getScheme()))
				|| baseUri.getHost() == null) {
			throw new IllegalStateException("app.storage.supabase-url must be a valid HTTP(S) URI");
		}
	}

	private String bearerHeader() {
		return "Bearer " + properties.supabaseServiceRoleKey();
	}

	private URI objectUri(String prefix, String storageKey) {
		if (storageKey == null || storageKey.isBlank()) {
			throw new IllegalArgumentException("storageKey must not be blank");
		}
		String encodedBucket = encodeSegment(properties.bucket());
		String encodedKey = Arrays.stream(storageKey.split("/")).filter(segment -> !segment.isEmpty())
				.map(SupabaseObjectStorageClient::encodeSegment).collect(Collectors.joining("/"));
		return URI.create(properties.supabaseUrl() + prefix + encodedBucket + "/" + encodedKey);
	}

	private String resolveSignedUrl(String signedPath) {
		if (signedPath.startsWith("https://") || signedPath.startsWith("http://")) {
			return signedPath;
		}
		if (signedPath.startsWith("/storage/v1/")) {
			return properties.supabaseUrl() + signedPath;
		}
		if (signedPath.startsWith("/")) {
			return properties.supabaseUrl() + "/storage/v1" + signedPath;
		}
		return properties.supabaseUrl() + "/storage/v1/" + signedPath;
	}

	private static String encodeSegment(String segment) {
		return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private record BatchSignedUrlItem(String error, String path, @JsonProperty("signedURL") String signedUrl) {
	}

	private record SignedUrlResponse(@JsonProperty("signedURL") String signedUrl) {
	}
}
