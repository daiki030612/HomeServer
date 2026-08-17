package com.example.homeserver.Service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HlsDownloadService {
	private static final Logger logger = LoggerFactory.getLogger(HlsDownloadService.class);
	private static final Pattern ATTRIBUTE_URI = Pattern.compile("URI=\"([^\"]+)\"");
	private static final Pattern BANDWIDTH = Pattern.compile("(?:^|,)BANDWIDTH=(\\d+)");
	private static final Pattern RESOLUTION = Pattern.compile("(?:^|,)RESOLUTION=(\\d+)x(\\d+)");
	private static final int MAX_PLAYLIST_DEPTH = 3;
	private static final int MAX_RESOURCES = 5000;
	private static final int MAX_CONCURRENCY = 16;
	private static final int MAX_RETRIES = 3;
	private static final long MAX_RETRY_AFTER_MILLIS = 5_000;

	private final SafeUrlHttpClient http;
	private final FfmpegProcessRunner processRunner;
	private final long maxBytes;
	private final Duration ffmpegTimeout;
	private final int concurrency;
	private final RetrySleeper retrySleeper;

	public HlsDownloadService(SafeUrlHttpClient http, FfmpegProcessRunner processRunner,
			@Value("${video.url-import.max-bytes:5368709120}") long maxBytes,
			@Value("${video.url-import.ffmpeg-timeout:PT30M}") Duration ffmpegTimeout,
			@Value("${video.url-import.hls-concurrency:6}") int concurrency) {
		this(http, processRunner, maxBytes, ffmpegTimeout, concurrency, Thread::sleep);
	}

	HlsDownloadService(SafeUrlHttpClient http, FfmpegProcessRunner processRunner,
			long maxBytes, Duration ffmpegTimeout, int concurrency, RetrySleeper retrySleeper) {
		this.http = http;
		this.processRunner = processRunner;
		this.maxBytes = maxBytes;
		this.ffmpegTimeout = ffmpegTimeout;
		this.concurrency = Math.max(1, Math.min(MAX_CONCURRENCY, concurrency));
		this.retrySleeper = retrySleeper;
	}

	public Path downloadAsMp4(URI playlistUri, Path workDirectory) {
		return downloadAsMp4(playlistUri, workDirectory, VideoSourceRequestContext.EMPTY);
	}

	public Path downloadAsMp4(URI playlistUri, Path workDirectory, VideoSourceRequestContext requestContext) {
		return downloadAsMp4(playlistUri, workDirectory, requestContext, ProgressListener.NOOP);
	}

	public Path downloadAsMp4(URI playlistUri, Path workDirectory, VideoSourceRequestContext requestContext,
			ProgressListener progressListener) {
		long startedNanos = System.nanoTime();
		try {
			long downloadDeadline = System.nanoTime() + ffmpegTimeout.toNanos();
			SafeUrlHttpClient.SharedDownloadBudget budget = new SafeUrlHttpClient.SharedDownloadBudget(maxBytes);
			Path mediaPlaylist = localizePlaylist(playlistUri, workDirectory, 0, startedNanos, downloadDeadline,
					requestContext == null ? VideoSourceRequestContext.EMPTY : requestContext,
					budget, progressListener == null ? ProgressListener.NOOP : progressListener);
			Path output = workDirectory.resolve("downloaded.mp4");
			List<String> command = List.of(
					"ffmpeg", "-nostdin", "-hide_banner", "-y",
					"-protocol_whitelist", "file,crypto,data",
					"-allowed_extensions", "ALL",
					"-i", mediaPlaylist.toString(),
					"-c", "copy", "-movflags", "+faststart", output.toString());
			FfmpegProcessRunner.ProcessResult result = processRunner.run(command, ffmpegTimeout);
			if (result.exitCode() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
				logger.error("HLS FFmpeg failed: exitCode={}, stderrTail={} ",
						result.exitCode(), safeStderrTail(result.standardError()));
				throw new VideoUrlImportException(VideoUrlImportException.Reason.FFMPEG_FAILED,
						"HLS動画をMP4へ変換できませんでした。");
			}
			return output;
		} catch (VideoUrlImportException e) {
			if (e.getReason() == VideoUrlImportException.Reason.INVALID_URL
					|| e.getReason() == VideoUrlImportException.Reason.UNSUPPORTED_SOURCE
					|| e.getReason() == VideoUrlImportException.Reason.FFMPEG_FAILED) throw e;
			logTerminalFailure(playlistUri, startedNanos, e);
			throw new VideoUrlImportException(VideoUrlImportException.Reason.HLS_DOWNLOAD_FAILED,
					"HLS動画を取得できませんでした。", e);
		} catch (Exception e) {
			logTerminalFailure(playlistUri, startedNanos, e);
			throw new VideoUrlImportException(VideoUrlImportException.Reason.HLS_DOWNLOAD_FAILED,
					"HLS動画を取得できませんでした。", e);
		}
	}

	private void logTerminalFailure(URI playlistUri, long startedNanos, Throwable error) {
		Integer status = httpStatus(error);
		FailureCategory category = status == null ? classify(error)
				: isTransientStatus(status) ? FailureCategory.HTTP_TRANSIENT : FailureCategory.HTTP_PERMANENT;
		logger.error("HLS download failed: source={}, status={}, exception={}, category={}, elapsedMs={}",
				safeUri(playlistUri), status == null ? "n/a" : status,
				rootCause(error).getClass().getSimpleName(), category, elapsedMillis(startedNanos));
	}

	private Path localizePlaylist(URI playlistUri, Path directory, int depth, long startedNanos, long deadlineNanos,
			VideoSourceRequestContext requestContext, SafeUrlHttpClient.SharedDownloadBudget budget,
			ProgressListener progressListener) throws Exception {
		ensureWithinDeadline(deadlineNanos);
		if (depth > MAX_PLAYLIST_DEPTH) throw new IOException("HLS playlist nesting is too deep");
		HlsResourceType requestedType = depth == 0
				? HlsResourceType.MASTER_PLAYLIST : HlsResourceType.VARIANT_PLAYLIST;
		SafeUrlHttpClient.TextResponse response = executeWithRetry(
				new ResourceRequest(requestedType, -1, playlistUri), startedNanos, deadlineNanos,
				() -> http.getText(playlistUri, Math.min(maxBytes, 2 * 1024 * 1024), requestContext,
						SafeUrlHttpClient.ImportStage.HLS_PLAYLIST));
		List<String> lines = response.body().lines().toList();
		Variant variant = selectHighestVariant(lines);
		if (variant != null) {
			return localizePlaylist(response.finalUri().resolve(variant.uri()), directory, depth + 1,
					startedNanos, deadlineNanos, requestContext, budget, progressListener);
		}
		logger.debug("HLS media playlist parsed: resourceType={}, source={}, elapsedMs={}",
				HlsResourceType.MEDIA_PLAYLIST, safeUri(response.finalUri()), elapsedMillis(startedNanos));
		if (lines.stream().anyMatch(line -> line.startsWith("#EXT-X-BYTERANGE"))) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.UNSUPPORTED_SOURCE,
					"このHLSのbyte-range形式にはまだ対応していません。");
		}

		MediaPlan plan = createMediaPlan(response.finalUri(), lines, directory);
		downloadResources(plan.resources(), requestContext, startedNanos, deadlineNanos, budget, progressListener);
		Path localPlaylist = directory.resolve("media-" + depth + ".m3u8");
		Files.write(localPlaylist, plan.localizedLines(), StandardCharsets.UTF_8);
		return localPlaylist;
	}

	private MediaPlan createMediaPlan(URI playlistUri, List<String> lines, Path directory) throws IOException {
		Map<URI, PlannedResource> resources = new LinkedHashMap<>();
		List<String> localized = new ArrayList<>(lines.size());
		int references = 0;
		int segmentIndex = 0;
		for (String originalLine : lines) {
			String line = originalLine;
			if (line.isBlank()) {
				localized.add(line);
				continue;
			}
			if (!line.startsWith("#")) {
				if (++references > MAX_RESOURCES) throw new IOException("HLS resource limit exceeded");
				PlannedResource resource = planResource(playlistUri.resolve(line.trim()), directory,
						resources, ResourceKind.MEDIA_SEGMENT, segmentIndex++);
				localized.add(resource.localName());
				continue;
			}
			Matcher matcher = ATTRIBUTE_URI.matcher(line);
			if (matcher.find()) {
				if (++references > MAX_RESOURCES) throw new IOException("HLS resource limit exceeded");
				ResourceKind kind = line.startsWith("#EXT-X-KEY") ? ResourceKind.KEY
						: line.startsWith("#EXT-X-MAP") ? ResourceKind.MAP : ResourceKind.AUXILIARY;
				PlannedResource resource = planResource(playlistUri.resolve(matcher.group(1)), directory,
						resources, kind, -1);
				line = matcher.replaceFirst(Matcher.quoteReplacement("URI=\"" + resource.localName() + "\""));
			}
			localized.add(line);
		}
		return new MediaPlan(localized, List.copyOf(resources.values()));
	}

	private PlannedResource planResource(URI uri, Path directory, Map<URI, PlannedResource> resources,
			ResourceKind kind, int segmentIndex) {
		URI normalized = uri.normalize();
		return resources.computeIfAbsent(normalized, key -> {
			int index = resources.size();
			String localName = String.format("resource-%05d%s", index, localExtension(key.getPath(), kind));
			return new PlannedResource(key, localName, directory.resolve(localName), kind, segmentIndex);
		});
	}

	private void downloadResources(List<PlannedResource> resources, VideoSourceRequestContext requestContext,
			long startedNanos, long deadlineNanos, SafeUrlHttpClient.SharedDownloadBudget budget,
			ProgressListener progressListener) throws Exception {
		if (resources.isEmpty()) {
			publishProgress(progressListener, new HlsProgress(0, 0, budget.consumedBytes()));
			return;
		}
		int poolSize = Math.min(concurrency, resources.size());
		AtomicInteger threadNumber = new AtomicInteger();
		ExecutorService executor = Executors.newFixedThreadPool(poolSize, runnable ->
				Thread.ofPlatform().daemon().name("hls-import-" + threadNumber.incrementAndGet()).unstarted(runnable));
		CompletionService<PlannedResource> completion = new ExecutorCompletionService<>(executor);
		List<Future<PlannedResource>> futures = new ArrayList<>(resources.size());
		int totalSegments = (int) resources.stream()
				.filter(resource -> resource.kind() == ResourceKind.MEDIA_SEGMENT).count();
		AtomicInteger completedSegments = new AtomicInteger();
		AtomicReference<HlsResourceFailure> firstFailure = new AtomicReference<>();
		publishProgress(progressListener, new HlsProgress(totalSegments, 0, budget.consumedBytes()));
		try {
			for (PlannedResource resource : resources) {
				futures.add(completion.submit(() -> {
					try {
						executeWithRetry(resource.request(), startedNanos, deadlineNanos, () ->
								http.download(resource.uri(), resource.target(), maxBytes, requestContext,
										SafeUrlHttpClient.ImportStage.HLS_RESOURCE, budget));
					} catch (Exception e) {
						firstFailure.compareAndSet(null, new HlsResourceFailure(resource.request(), e));
						throw e;
					}
					ensureWithinDeadline(deadlineNanos);
					int done = resource.kind() == ResourceKind.MEDIA_SEGMENT
							? completedSegments.incrementAndGet() : completedSegments.get();
					publishProgress(progressListener,
							new HlsProgress(totalSegments, done, budget.consumedBytes()));
					return resource;
				}));
			}
			for (int i = 0; i < resources.size(); i++) {
				long remainingNanos = deadlineNanos - System.nanoTime();
				if (remainingNanos <= 0) throw new IOException("HLS download timed out");
				Future<PlannedResource> finished = completion.poll(remainingNanos, TimeUnit.NANOSECONDS);
				if (finished == null) throw new IOException("HLS download timed out");
				finished.get();
			}
		} catch (ExecutionException | CancellationException e) {
			cancelAll(futures);
			HlsResourceFailure failure = firstFailure.get();
			Throwable cause = failure == null ? e.getCause() : failure.cause();
			if (cause == null) cause = e;
			if (failure != null) {
				logger.error("HLS parallel download aborted: firstResourceType={}, index={}, source={}, "
						+ "exception={}, category={}, elapsedMs={}",
						failure.resource().type(), failure.resource().index(), safeUri(failure.resource().uri()),
						rootCause(cause).getClass().getSimpleName(), classify(cause), elapsedMillis(startedNanos));
			}
			if (cause instanceof Exception exception) throw exception;
			throw new IOException("HLS resource download failed", cause);
		} catch (Exception e) {
			cancelAll(futures);
			throw e;
		} finally {
			executor.shutdownNow();
			try {
				executor.awaitTermination(5, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private void cancelAll(List<? extends Future<?>> futures) {
		for (Future<?> future : futures) future.cancel(true);
	}

	private <T> T executeWithRetry(ResourceRequest resource, long startedNanos, long deadlineNanos,
			CheckedSupplier<T> request) throws Exception {
		for (int attempt = 1; ; attempt++) {
			ensureWithinDeadline(deadlineNanos);
			try {
				return request.get();
			} catch (Exception error) {
				FailureCategory category = classify(error);
				if (category == FailureCategory.INTERRUPTED) Thread.currentThread().interrupt();
				Integer status = httpStatus(error);
				boolean retry = attempt <= MAX_RETRIES && isRetryable(error, status, category);
				FailureCategory loggedCategory = status == null ? category
						: isTransientStatus(status) ? FailureCategory.HTTP_TRANSIENT : FailureCategory.HTTP_PERMANENT;
				logger.warn("HLS resource request failed: resourceType={}, index={}, source={}, status={}, "
						+ "exception={}, category={}, retryCount={}, willRetry={}, elapsedMs={}",
						resource.type(), resource.index(), safeUri(resource.uri()),
						status == null ? "n/a" : status, rootCause(error).getClass().getSimpleName(),
						loggedCategory, attempt - 1, retry, elapsedMillis(startedNanos));
				if (!retry) throw error;
				long delayMillis = retryDelayMillis(error, attempt);
				if (System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis) >= deadlineNanos) throw error;
				try {
					retrySleeper.sleep(delayMillis);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw interrupted;
				}
			}
		}
	}

	private boolean isRetryable(Throwable error, Integer status, FailureCategory category) {
		if (Thread.currentThread().isInterrupted() || category == FailureCategory.INTERRUPTED
				|| hasReason(error, VideoUrlImportException.Reason.INVALID_URL)
				|| hasReason(error, VideoUrlImportException.Reason.SIZE_LIMIT_EXCEEDED)) return false;
		if (status != null) return isTransientStatus(status);
		return category == FailureCategory.TIMEOUT || category == FailureCategory.CONNECTION
				|| category == FailureCategory.TRANSIENT_IO;
	}

	private boolean isTransientStatus(int status) {
		return status == 408 || status == 429 || status == 500
				|| status == 502 || status == 503 || status == 504;
	}

	private FailureCategory classify(Throwable error) {
		boolean ioFailure = false;
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof InterruptedException) return FailureCategory.INTERRUPTED;
			if (current instanceof HttpTimeoutException) return FailureCategory.TIMEOUT;
			if (current instanceof ConnectException) return FailureCategory.CONNECTION;
			if (current instanceof IOException) ioFailure = true;
		}
		return ioFailure ? FailureCategory.TRANSIENT_IO : FailureCategory.PERMANENT;
	}

	private Integer httpStatus(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof SafeUrlHttpClient.HttpStatusException status) return status.statusCode();
		}
		return null;
	}

	private long retryDelayMillis(Throwable error, int attempt) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof SafeUrlHttpClient.HttpStatusException status && status.retryAfter() != null) {
				try {
					return Math.min(MAX_RETRY_AFTER_MILLIS, Math.max(0, status.retryAfter().toMillis()));
				} catch (ArithmeticException tooLarge) {
					return MAX_RETRY_AFTER_MILLIS;
				}
			}
		}
		return 500L << Math.min(2, attempt - 1);
	}

	private boolean hasReason(Throwable error, VideoUrlImportException.Reason reason) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof VideoUrlImportException importError && importError.getReason() == reason) return true;
		}
		return false;
	}

	private Throwable rootCause(Throwable error) {
		Throwable root = error;
		while (root.getCause() != null && root.getCause() != root) root = root.getCause();
		return root;
	}

	private String safeUri(URI uri) {
		if (uri == null) return "<unknown>";
		String path = uri.getPath() == null ? "" : uri.getPath();
		StringBuilder safePath = new StringBuilder();
		for (String part : path.split("/")) {
			if (part.isEmpty()) continue;
			safePath.append('/').append(part.length() > 32 ? "[redacted]"
					: part.replaceAll("[^A-Za-z0-9._~-]", "_"));
		}
		return uri.getScheme() + "://" + uri.getHost() + safePath;
	}

	private long elapsedMillis(long startedNanos) {
		return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - startedNanos));
	}

	private void publishProgress(ProgressListener listener, HlsProgress progress) {
		try {
			listener.onProgress(progress);
		} catch (RuntimeException e) {
			logger.warn("HLS progress listener failed", e);
		}
	}

	private Variant selectHighestVariant(List<String> lines) throws IOException {
		List<Variant> variants = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i).trim();
			if (!line.startsWith("#EXT-X-STREAM-INF")) continue;
			String attributes = line.substring(line.indexOf(':') + 1);
			String uri = nextUriLine(lines, i + 1);
			long bandwidth = matchLong(BANDWIDTH, attributes);
			Matcher resolution = RESOLUTION.matcher(attributes);
			long pixels = resolution.find() ? parseLong(resolution.group(1)) * parseLong(resolution.group(2)) : -1;
			variants.add(new Variant(uri, pixels, bandwidth));
		}
		return variants.stream().max(Comparator.comparingLong(Variant::pixels)
				.thenComparingLong(Variant::bandwidth)).orElse(null);
	}

	private long matchLong(Pattern pattern, String value) {
		Matcher matcher = pattern.matcher(value);
		return matcher.find() ? parseLong(matcher.group(1)) : -1;
	}

	private long parseLong(String value) {
		try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return -1; }
	}

	private void ensureWithinDeadline(long deadlineNanos) throws IOException {
		if (System.nanoTime() >= deadlineNanos || Thread.currentThread().isInterrupted()) {
			throw new IOException("HLS download timed out or cancelled");
		}
	}

	private String nextUriLine(List<String> lines, int start) throws IOException {
		for (int i = start; i < lines.size(); i++) {
			String line = lines.get(i).trim();
			if (!line.isBlank() && !line.startsWith("#")) return line;
		}
		throw new IOException("Master playlist has no variant URI");
	}

	private String safeExtension(String path) {
		int slash = path.lastIndexOf('/');
		int dot = path.lastIndexOf('.');
		if (dot > slash && path.length() - dot <= 8) {
			String extension = path.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
			if (!extension.isBlank()) return extension;
		}
		return ".bin";
	}

	private String localExtension(String path, ResourceKind kind) {
		String extension = safeExtension(path);
		if (kind == ResourceKind.MEDIA_SEGMENT
				&& (extension.equalsIgnoreCase(".jpeg") || extension.equalsIgnoreCase(".jpg"))) {
			return ".ts";
		}
		return extension;
	}

	private String safeStderrTail(String standardError) {
		if (standardError == null || standardError.isBlank()) return "<empty>";
		List<String> lines = standardError.lines().toList();
		int start = Math.max(0, lines.size() - 40);
		String tail = String.join(System.lineSeparator(), lines.subList(start, lines.size()));
		tail = tail.replaceAll("(?i)https?://\\S+", "[redacted-url]")
				.replaceAll("(?i)(authorization|cookie)\\s*:[^\\r\\n]*", "$1: [redacted]");
		return tail.length() <= 16_384 ? tail : tail.substring(tail.length() - 16_384);
	}

	public record HlsProgress(int totalSegments, int completedSegments, long downloadedBytes) {}

	@FunctionalInterface
	public interface ProgressListener {
		ProgressListener NOOP = progress -> { };
		void onProgress(HlsProgress progress);
	}

	private record Variant(String uri, long pixels, long bandwidth) {}
	private enum ResourceKind { MEDIA_SEGMENT, KEY, MAP, AUXILIARY }
	private enum HlsResourceType { MASTER_PLAYLIST, VARIANT_PLAYLIST, MEDIA_PLAYLIST, MEDIA_SEGMENT, KEY, MAP }
	private enum FailureCategory {
		HTTP_TRANSIENT, HTTP_PERMANENT, TIMEOUT, CONNECTION, TRANSIENT_IO, INTERRUPTED, PERMANENT
	}
	private record ResourceRequest(HlsResourceType type, int index, URI uri) {}
	private record HlsResourceFailure(ResourceRequest resource, Throwable cause) {}
	private record PlannedResource(URI uri, String localName, Path target, ResourceKind kind, int segmentIndex) {
		ResourceRequest request() {
			HlsResourceType type = switch (kind) {
			case MEDIA_SEGMENT -> HlsResourceType.MEDIA_SEGMENT;
			case KEY -> HlsResourceType.KEY;
			case MAP, AUXILIARY -> HlsResourceType.MAP;
			};
			return new ResourceRequest(type, segmentIndex, uri);
		}
	}
	private record MediaPlan(List<String> localizedLines, List<PlannedResource> resources) {}
	@FunctionalInterface interface CheckedSupplier<T> { T get() throws Exception; }
	@FunctionalInterface interface RetrySleeper { void sleep(long millis) throws InterruptedException; }
}
