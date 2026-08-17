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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SafeUrlHttpClient {
	private static final Logger logger = LoggerFactory.getLogger(SafeUrlHttpClient.class);
	private static final int MAX_REDIRECTS = 5;
	private static final String DEFAULT_USER_AGENT = "HomeServer-VideoImporter/1.0";
	private final UrlSafetyValidator validator;
	private final HttpClient client;
	private final String defaultUserAgent;

	@Autowired
	public SafeUrlHttpClient(UrlSafetyValidator validator,
			@Value("${video.url-import.user-agent:" + DEFAULT_USER_AGENT + "}") String defaultUserAgent) {
		this(validator, HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build(), defaultUserAgent);
	}

	SafeUrlHttpClient(UrlSafetyValidator validator, HttpClient client) {
		this(validator, client, DEFAULT_USER_AGENT);
	}

	SafeUrlHttpClient(UrlSafetyValidator validator, HttpClient client, String defaultUserAgent) {
		this.validator = validator;
		this.client = client;
		this.defaultUserAgent = requireSafeUserAgent(defaultUserAgent);
	}

	public TextResponse getText(URI uri, long maxBytes) {
		return getText(uri, maxBytes, VideoSourceRequestContext.EMPTY, ImportStage.PAGE);
	}

	public TextResponse getText(URI uri, long maxBytes, VideoSourceRequestContext context, ImportStage stage) {
		Path temporary = null;
		try {
			Response response = execute(uri, maxBytes, null, context, stage);
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
		return download(uri, destination, maxBytes, VideoSourceRequestContext.EMPTY, ImportStage.MEDIA);
	}

	public DownloadResponse download(URI uri, Path destination, long maxBytes,
			VideoSourceRequestContext context, ImportStage stage) {
		return download(uri, destination, maxBytes, context, stage, null);
	}

	public DownloadResponse download(URI uri, Path destination, long maxBytes,
			VideoSourceRequestContext context, ImportStage stage, SharedDownloadBudget sharedBudget) {
		try {
			Response response = execute(uri, maxBytes, destination, context, stage, sharedBudget);
			return new DownloadResponse(response.finalUri(), response.contentType(), Files.size(destination));
		} catch (ResponseSizeLimitException e) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.SIZE_LIMIT_EXCEEDED,
					"動画が保存可能な容量制限を超えています。", e);
		} catch (IOException e) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.MEDIA_DOWNLOAD_FAILED,
					"動画データを取得できませんでした。", e);
		}
	}

	private Response execute(URI initialUri, long maxBytes, Path destination,
			VideoSourceRequestContext context, ImportStage stage) throws IOException {
		return execute(initialUri, maxBytes, destination, context, stage, null);
	}

	private Response execute(URI initialUri, long maxBytes, Path destination,
			VideoSourceRequestContext context, ImportStage stage,
			SharedDownloadBudget sharedBudget) throws IOException {
		VideoSourceRequestContext safeContext = context == null ? VideoSourceRequestContext.EMPTY : context;
		ImportStage safeStage = stage == null ? ImportStage.MEDIA : stage;
		URI current = initialUri;
		for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
			current = validator.validate(current);
			HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(current)
					.timeout(Duration.ofSeconds(60))
					.header("User-Agent", safeContext.userAgent() == null
							? defaultUserAgent : safeContext.userAgent())
					.header("Accept", safeStage.accept())
					.GET();
			if (safeContext.referer() != null) {
				requestBuilder.header("Referer", safeContext.referer().toASCIIString());
			}
			HttpRequest request = requestBuilder.build();
			HttpResponse<InputStream> response;
			try {
				response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("HTTP request interrupted", e);
			}
			int status = response.statusCode();
			logger.debug("URL import HTTP response: stage={}, host={}, status={}",
					safeStage, current.getHost(), status);
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
				logger.warn("URL import HTTP failure: stage={}, host={}, status={}",
						safeStage, current.getHost(), status);
				response.body().close();
				throw new HttpStatusException(status, parseRetryAfter(response));
			}
			long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
			if (declaredLength > maxBytes) {
				response.body().close();
				throw new ResponseSizeLimitException();
			}
			Path target = destination == null ? Files.createTempFile("video-url-text-", ".tmp") : destination;
			copyLimited(response.body(), target, maxBytes, sharedBudget);
			return new Response(current, target,
					response.headers().firstValue("Content-Type").orElse(""));
		}
		throw new IOException("Too many redirects");
	}

	private String requireSafeUserAgent(String value) {
		String userAgent = value == null ? "" : value.trim();
		if (userAgent.isEmpty() || userAgent.length() > 512
				|| userAgent.indexOf('\r') >= 0 || userAgent.indexOf('\n') >= 0) {
			throw new IllegalArgumentException("Invalid configured User-Agent");
		}
		return userAgent;
	}

	private void copyLimited(InputStream input, Path destination, long maxBytes) throws IOException {
		copyLimited(input, destination, maxBytes, null);
	}

	private void copyLimited(InputStream input, Path destination, long maxBytes,
			SharedDownloadBudget sharedBudget) throws IOException {
		long total = 0;
		long budgetClaimed = 0;
		byte[] buffer = new byte[64 * 1024];
		try (input; var output = Files.newOutputStream(destination,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
			int read;
			while ((read = input.read(buffer)) != -1) {
				total += read;
				if (total > maxBytes) throw new ResponseSizeLimitException();
				if (sharedBudget != null) {
					sharedBudget.claim(read);
					budgetClaimed += read;
				}
				output.write(buffer, 0, read);
			}
		} catch (IOException e) {
			if (sharedBudget != null && budgetClaimed > 0) sharedBudget.release(budgetClaimed);
			Files.deleteIfExists(destination);
			throw e;
		}
	}

	private Duration parseRetryAfter(HttpResponse<?> response) {
		Optional<String> value = response.headers().firstValue("Retry-After");
		if (value.isEmpty()) return null;
		String raw = value.get().trim();
		try {
			long seconds = Long.parseLong(raw);
			return seconds < 0 ? null : Duration.ofSeconds(seconds);
		} catch (NumberFormatException ignored) {
			try {
				Duration duration = Duration.between(ZonedDateTime.now(),
						ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME));
				return duration.isNegative() ? Duration.ZERO : duration;
			} catch (DateTimeParseException ignoredDate) {
				return null;
			}
		}
	}

	private record Response(URI finalUri, Path file, String contentType) {}
	private static class ResponseSizeLimitException extends IOException {
		private static final long serialVersionUID = 1L;
	}
	public static final class HttpStatusException extends IOException {
		private static final long serialVersionUID = 1L;
		private final int statusCode;
		private final Duration retryAfter;

		public HttpStatusException(int statusCode, Duration retryAfter) {
			super("HTTP request failed with status " + statusCode);
			this.statusCode = statusCode;
			this.retryAfter = retryAfter;
		}

		public int statusCode() { return statusCode; }
		public Duration retryAfter() { return retryAfter; }
	}
	public record TextResponse(URI finalUri, String body, String contentType) {}
	public record DownloadResponse(URI finalUri, String contentType, long bytes) {}

	public static final class SharedDownloadBudget {
		private final long limit;
		private final AtomicLong consumed = new AtomicLong();

		public SharedDownloadBudget(long limit) {
			if (limit <= 0) throw new IllegalArgumentException("Download limit must be positive");
			this.limit = limit;
		}

		private void claim(long bytes) throws ResponseSizeLimitException {
			while (true) {
				long current = consumed.get();
				if (bytes > limit - current) throw new ResponseSizeLimitException();
				if (consumed.compareAndSet(current, current + bytes)) return;
			}
		}

		private void release(long bytes) {
			if (bytes <= 0) return;
			consumed.updateAndGet(current -> Math.max(0, current - bytes));
		}

		public long consumedBytes() { return consumed.get(); }
		public long limitBytes() { return limit; }
	}

	public enum ImportStage {
		PAGE("text/html,application/xhtml+xml;q=0.9,*/*;q=0.5"),
		HLS_PLAYLIST("application/vnd.apple.mpegurl,application/x-mpegURL;q=0.9,*/*;q=0.5"),
		HLS_RESOURCE("video/*,application/octet-stream;q=0.9,*/*;q=0.5"),
		MEDIA("video/*,application/octet-stream;q=0.9,*/*;q=0.5");

		private final String accept;
		ImportStage(String accept) { this.accept = accept; }
		String accept() { return accept; }
	}
}
