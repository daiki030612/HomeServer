package com.example.homeserver.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HlsDownloadService {
	private static final Pattern ATTRIBUTE_URI = Pattern.compile("URI=\"([^\"]+)\"");
	private static final int MAX_PLAYLIST_DEPTH = 3;
	private static final int MAX_RESOURCES = 5000;

	private final SafeUrlHttpClient http;
	private final FfmpegProcessRunner processRunner;
	private final long maxBytes;
	private final Duration ffmpegTimeout;

	public HlsDownloadService(SafeUrlHttpClient http, FfmpegProcessRunner processRunner,
			@Value("${video.url-import.max-bytes:5368709120}") long maxBytes,
			@Value("${video.url-import.ffmpeg-timeout:PT30M}") Duration ffmpegTimeout) {
		this.http = http;
		this.processRunner = processRunner;
		this.maxBytes = maxBytes;
		this.ffmpegTimeout = ffmpegTimeout;
	}

	public Path downloadAsMp4(URI playlistUri, Path workDirectory) {
		return downloadAsMp4(playlistUri, workDirectory, VideoSourceRequestContext.EMPTY);
	}

	public Path downloadAsMp4(URI playlistUri, Path workDirectory, VideoSourceRequestContext requestContext) {
		try {
			long downloadDeadline = System.nanoTime() + ffmpegTimeout.toNanos();
			Path mediaPlaylist = localizePlaylist(playlistUri, workDirectory, 0, downloadDeadline,
					requestContext == null ? VideoSourceRequestContext.EMPTY : requestContext);
			Path output = workDirectory.resolve("downloaded.mp4");
			List<String> command = List.of(
					"ffmpeg", "-nostdin", "-hide_banner", "-y",
					"-protocol_whitelist", "file,crypto,data",
					"-allowed_extensions", "ALL",
					"-i", mediaPlaylist.toString(),
					"-c", "copy", "-movflags", "+faststart", output.toString());
			FfmpegProcessRunner.ProcessResult result = processRunner.run(command, ffmpegTimeout);
			if (result.exitCode() != 0 || !Files.isRegularFile(output) || Files.size(output) == 0) {
				throw new VideoUrlImportException(VideoUrlImportException.Reason.FFMPEG_FAILED,
						"HLS動画をMP4へ変換できませんでした。");
			}
			return output;
		} catch (VideoUrlImportException e) {
			if (e.getReason() == VideoUrlImportException.Reason.INVALID_URL
					|| e.getReason() == VideoUrlImportException.Reason.UNSUPPORTED_SOURCE
					|| e.getReason() == VideoUrlImportException.Reason.FFMPEG_FAILED) {
				throw e;
			}
			throw new VideoUrlImportException(VideoUrlImportException.Reason.HLS_DOWNLOAD_FAILED,
					"HLS動画を取得できませんでした。", e);
		} catch (Exception e) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.HLS_DOWNLOAD_FAILED,
					"HLS動画を取得できませんでした。", e);
		}
	}

	private Path localizePlaylist(URI playlistUri, Path directory, int depth, long deadlineNanos,
			VideoSourceRequestContext requestContext) throws IOException {
		ensureWithinDeadline(deadlineNanos);
		if (depth > MAX_PLAYLIST_DEPTH) throw new IOException("HLS playlist nesting is too deep");
		SafeUrlHttpClient.TextResponse response = http.getText(playlistUri,
				Math.min(maxBytes, 2 * 1024 * 1024), requestContext,
				SafeUrlHttpClient.ImportStage.HLS_PLAYLIST);
		List<String> lines = response.body().lines().toList();
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith("#EXT-X-STREAM-INF")) {
				String child = nextUriLine(lines, i + 1);
				return localizePlaylist(response.finalUri().resolve(child), directory, depth + 1,
						deadlineNanos, requestContext);
			}
			if (lines.get(i).startsWith("#EXT-X-BYTERANGE")) {
				throw new VideoUrlImportException(VideoUrlImportException.Reason.UNSUPPORTED_SOURCE,
						"このHLSのbyte-range形式にはまだ対応していません。");
			}
		}

		long remaining = maxBytes;
		int resourceCount = 0;
		Map<URI, String> localNames = new HashMap<>();
		List<String> localized = new ArrayList<>(lines.size());
		for (String line : lines) {
			ensureWithinDeadline(deadlineNanos);
			if (line.isBlank()) {
				localized.add(line);
				continue;
			}
			if (!line.startsWith("#")) {
				Resource resource = downloadResource(response.finalUri().resolve(line.trim()), directory,
						localNames, resourceCount++, remaining, requestContext);
				remaining -= resource.bytes();
				localized.add(resource.localName());
				continue;
			}
			Matcher matcher = ATTRIBUTE_URI.matcher(line);
			if (matcher.find()) {
				Resource resource = downloadResource(response.finalUri().resolve(matcher.group(1)), directory,
						localNames, resourceCount++, remaining, requestContext);
				remaining -= resource.bytes();
				line = matcher.replaceFirst(Matcher.quoteReplacement("URI=\"" + resource.localName() + "\""));
			}
			localized.add(line);
		}
		Path localPlaylist = directory.resolve("media-" + depth + ".m3u8");
		Files.write(localPlaylist, localized, StandardCharsets.UTF_8);
		return localPlaylist;
	}

	private void ensureWithinDeadline(long deadlineNanos) throws IOException {
		if (System.nanoTime() >= deadlineNanos) throw new IOException("HLS download timed out");
	}

	private Resource downloadResource(URI uri, Path directory, Map<URI, String> localNames,
			int index, long remaining, VideoSourceRequestContext requestContext) throws IOException {
		if (index >= MAX_RESOURCES || remaining <= 0) throw new IOException("HLS resource limit exceeded");
		URI normalized = uri.normalize();
		String existing = localNames.get(normalized);
		if (existing != null) return new Resource(existing, 0);
		String extension = safeExtension(normalized.getPath());
		String localName = String.format("resource-%05d%s", index, extension);
		Path target = directory.resolve(localName);
		SafeUrlHttpClient.DownloadResponse downloaded = http.download(normalized, target, remaining,
				requestContext, SafeUrlHttpClient.ImportStage.HLS_RESOURCE);
		localNames.put(downloaded.finalUri().normalize(), localName);
		return new Resource(localName, downloaded.bytes());
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

	private record Resource(String localName, long bytes) {}
}
