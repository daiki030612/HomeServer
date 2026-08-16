package com.example.homeserver.Service;

import java.io.IOException;
import java.net.URI;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

	private final SafeUrlHttpClient http;
	private final FfmpegProcessRunner processRunner;
	private final long maxBytes;
	private final Duration ffmpegTimeout;
	private final int concurrency;

	public HlsDownloadService(SafeUrlHttpClient http, FfmpegProcessRunner processRunner,
			@Value("${video.url-import.max-bytes:5368709120}") long maxBytes,
			@Value("${video.url-import.ffmpeg-timeout:PT30M}") Duration ffmpegTimeout,
			@Value("${video.url-import.hls-concurrency:6}") int concurrency) {
		this.http = http;
		this.processRunner = processRunner;
		this.maxBytes = maxBytes;
		this.ffmpegTimeout = ffmpegTimeout;
		this.concurrency = Math.max(1, Math.min(MAX_CONCURRENCY, concurrency));
	}

	public Path downloadAsMp4(URI playlistUri, Path workDirectory) {
		return downloadAsMp4(playlistUri, workDirectory, VideoSourceRequestContext.EMPTY);
	}

	public Path downloadAsMp4(URI playlistUri, Path workDirectory, VideoSourceRequestContext requestContext) {
		return downloadAsMp4(playlistUri, workDirectory, requestContext, ProgressListener.NOOP);
	}

	public Path downloadAsMp4(URI playlistUri, Path workDirectory, VideoSourceRequestContext requestContext,
			ProgressListener progressListener) {
		try {
			long downloadDeadline = System.nanoTime() + ffmpegTimeout.toNanos();
			SafeUrlHttpClient.SharedDownloadBudget budget = new SafeUrlHttpClient.SharedDownloadBudget(maxBytes);
			Path mediaPlaylist = localizePlaylist(playlistUri, workDirectory, 0, downloadDeadline,
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
			throw new VideoUrlImportException(VideoUrlImportException.Reason.HLS_DOWNLOAD_FAILED,
					"HLS動画を取得できませんでした。", e);
		} catch (Exception e) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.HLS_DOWNLOAD_FAILED,
					"HLS動画を取得できませんでした。", e);
		}
	}

	private Path localizePlaylist(URI playlistUri, Path directory, int depth, long deadlineNanos,
			VideoSourceRequestContext requestContext, SafeUrlHttpClient.SharedDownloadBudget budget,
			ProgressListener progressListener) throws Exception {
		ensureWithinDeadline(deadlineNanos);
		if (depth > MAX_PLAYLIST_DEPTH) throw new IOException("HLS playlist nesting is too deep");
		SafeUrlHttpClient.TextResponse response = http.getText(playlistUri,
				Math.min(maxBytes, 2 * 1024 * 1024), requestContext,
				SafeUrlHttpClient.ImportStage.HLS_PLAYLIST);
		List<String> lines = response.body().lines().toList();
		Variant variant = selectHighestVariant(lines);
		if (variant != null) {
			return localizePlaylist(response.finalUri().resolve(variant.uri()), directory, depth + 1,
					deadlineNanos, requestContext, budget, progressListener);
		}
		if (lines.stream().anyMatch(line -> line.startsWith("#EXT-X-BYTERANGE"))) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.UNSUPPORTED_SOURCE,
					"このHLSのbyte-range形式にはまだ対応していません。");
		}

		MediaPlan plan = createMediaPlan(response.finalUri(), lines, directory);
		downloadResources(plan.resources(), requestContext, deadlineNanos, budget, progressListener);
		Path localPlaylist = directory.resolve("media-" + depth + ".m3u8");
		Files.write(localPlaylist, plan.localizedLines(), StandardCharsets.UTF_8);
		return localPlaylist;
	}

	private MediaPlan createMediaPlan(URI playlistUri, List<String> lines, Path directory) throws IOException {
		Map<URI, PlannedResource> resources = new LinkedHashMap<>();
		List<String> localized = new ArrayList<>(lines.size());
		int references = 0;
		for (String originalLine : lines) {
			String line = originalLine;
			if (line.isBlank()) {
				localized.add(line);
				continue;
			}
			if (!line.startsWith("#")) {
				if (++references > MAX_RESOURCES) throw new IOException("HLS resource limit exceeded");
				PlannedResource resource = planResource(playlistUri.resolve(line.trim()), directory,
						resources, ResourceKind.MEDIA_SEGMENT);
				localized.add(resource.localName());
				continue;
			}
			Matcher matcher = ATTRIBUTE_URI.matcher(line);
			if (matcher.find()) {
				if (++references > MAX_RESOURCES) throw new IOException("HLS resource limit exceeded");
				PlannedResource resource = planResource(playlistUri.resolve(matcher.group(1)), directory,
						resources, ResourceKind.AUXILIARY);
				line = matcher.replaceFirst(Matcher.quoteReplacement("URI=\"" + resource.localName() + "\""));
			}
			localized.add(line);
		}
		return new MediaPlan(localized, List.copyOf(resources.values()));
	}

	private PlannedResource planResource(URI uri, Path directory, Map<URI, PlannedResource> resources,
			ResourceKind kind) {
		URI normalized = uri.normalize();
		return resources.computeIfAbsent(normalized, key -> {
			int index = resources.size();
			String localName = String.format("resource-%05d%s", index, localExtension(key.getPath(), kind));
			return new PlannedResource(key, localName, directory.resolve(localName), kind);
		});
	}

	private void downloadResources(List<PlannedResource> resources, VideoSourceRequestContext requestContext,
			long deadlineNanos, SafeUrlHttpClient.SharedDownloadBudget budget,
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
		publishProgress(progressListener, new HlsProgress(totalSegments, 0, budget.consumedBytes()));
		try {
			for (PlannedResource resource : resources) {
				futures.add(completion.submit(() -> {
					ensureWithinDeadline(deadlineNanos);
					http.download(resource.uri(), resource.target(), maxBytes, requestContext,
							SafeUrlHttpClient.ImportStage.HLS_RESOURCE, budget);
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
		} catch (ExecutionException e) {
			cancelAll(futures);
			Throwable cause = e.getCause();
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
	private enum ResourceKind { MEDIA_SEGMENT, AUXILIARY }
	private record PlannedResource(URI uri, String localName, Path target, ResourceKind kind) {}
	private record MediaPlan(List<String> localizedLines, List<PlannedResource> resources) {}
}
