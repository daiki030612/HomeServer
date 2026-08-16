package com.example.homeserver.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SafeUrlHttpClient {
	private static final int MAX_REDIRECTS = 5;
	private final UrlSafetyValidator validator;
	private final HttpClient client;

	@Autowired
	public SafeUrlHttpClient(UrlSafetyValidator validator) {
		this(validator, HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build());
	}

	SafeUrlHttpClient(UrlSafetyValidator validator, HttpClient client) {
		this.validator = validator;
		this.client = client;
	}

	public TextResponse getText(URI uri, long maxBytes) {
		Path temporary = null;
		try {
			Response response = execute(uri, maxBytes, null);
			temporary = response.file();
			return new TextResponse(response.finalUri(),
					Files.readString(response.file(), StandardCharsets.UTF_8), response.contentType());
		} catch (IOException e) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.PAGE_FETCH_FAILED,
					"動画ページを取得できませんでした。", e);
		} finally {
			if (temporary != null) {
				try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
			}
		}
	}

	public DownloadResponse download(URI uri, Path destination, long maxBytes) {
		try {
			Response response = execute(uri, maxBytes, destination);
			return new DownloadResponse(response.finalUri(), response.contentType(), Files.size(destination));
		} catch (ResponseSizeLimitException e) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.SIZE_LIMIT_EXCEEDED,
					"動画が保存可能な容量制限を超えています。", e);
		} catch (IOException e) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.MEDIA_DOWNLOAD_FAILED,
					"動画データを取得できませんでした。", e);
		}
	}

	private Response execute(URI initialUri, long maxBytes, Path destination) throws IOException {
		URI current = initialUri;
		for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
			current = validator.validate(current);
			HttpRequest request = HttpRequest.newBuilder(current)
					.timeout(Duration.ofSeconds(60))
					.header("User-Agent", "HomeServer-VideoImporter/1.0")
					.header("Accept", "text/html,application/vnd.apple.mpegurl,video/*;q=0.9,*/*;q=0.5")
					.GET().build();
			HttpResponse<InputStream> response;
			try {
				response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("HTTP request interrupted", e);
			}
			int status = response.statusCode();
			if (status >= 300 && status < 400) {
				response.body().close();
				Optional<String> location = response.headers().firstValue("Location");
				if (location.isEmpty() || redirect == MAX_REDIRECTS) {
					throw new IOException("Invalid or excessive redirect");
				}
				current = current.resolve(location.get());
				continue;
			}
			if (status < 200 || status >= 300) {
				response.body().close();
				throw new IOException("Unexpected HTTP status " + status);
			}
			long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
			if (declaredLength > maxBytes) {
				response.body().close();
				throw new ResponseSizeLimitException();
			}
			Path target = destination == null ? Files.createTempFile("video-url-text-", ".tmp") : destination;
			copyLimited(response.body(), target, maxBytes);
			return new Response(current, target,
					response.headers().firstValue("Content-Type").orElse(""));
		}
		throw new IOException("Too many redirects");
	}

	private void copyLimited(InputStream input, Path destination, long maxBytes) throws IOException {
		long total = 0;
		byte[] buffer = new byte[64 * 1024];
		try (input; var output = Files.newOutputStream(destination,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
			int read;
			while ((read = input.read(buffer)) != -1) {
				total += read;
				if (total > maxBytes) throw new ResponseSizeLimitException();
				output.write(buffer, 0, read);
			}
		} catch (IOException e) {
			Files.deleteIfExists(destination);
			throw e;
		}
	}

	private record Response(URI finalUri, Path file, String contentType) {}
	private static class ResponseSizeLimitException extends IOException {
		private static final long serialVersionUID = 1L;
	}
	public record TextResponse(URI finalUri, String body, String contentType) {}
	public record DownloadResponse(URI finalUri, String contentType, long bytes) {}
}
