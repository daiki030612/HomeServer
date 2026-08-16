package com.example.homeserver.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VideoUrlImportService {
	private final UrlSafetyValidator validator;
	private final List<VideoSourceExtractor> extractors;
	private final SafeUrlHttpClient http;
	private final HlsDownloadService hls;
	private final VideoService videos;
	private final long maxBytes;

	public VideoUrlImportService(UrlSafetyValidator validator, List<VideoSourceExtractor> extractors,
			SafeUrlHttpClient http, HlsDownloadService hls, VideoService videos,
			@Value("${video.url-import.max-bytes:5368709120}") long maxBytes) {
		this.validator = validator;
		this.extractors = extractors;
		this.http = http;
		this.hls = hls;
		this.videos = videos;
		this.maxBytes = maxBytes;
	}

	public void importVideo(String rawUrl, Long folderId) {
		URI pageUri = validator.validate(rawUrl);
		VideoSourceExtractor extractor = extractors.stream()
				.filter(candidate -> candidate.supports(pageUri))
				.findFirst()
				.orElseThrow(() -> new VideoUrlImportException(
						VideoUrlImportException.Reason.UNSUPPORTED_SOURCE,
						"この動画ページには対応していません。"));
		VideoSourceExtractor.ExtractedVideoSource source = extractor.extract(pageUri);

		Path workDirectory = null;
		try {
			workDirectory = Files.createTempDirectory("homeserver-url-import-");
			Path downloaded;
			if (source.kind() == VideoSourceExtractor.MediaKind.HLS) {
				downloaded = hls.downloadAsMp4(source.mediaUri(), workDirectory);
			} else {
				downloaded = workDirectory.resolve("downloaded.mp4");
				http.download(source.mediaUri(), downloaded, maxBytes);
			}
			try {
				videos.importDownloadedVideo(downloaded, source.title(), folderId);
			} catch (RuntimeException e) {
				VideoUrlImportException.Reason reason = hasDatabaseCause(e)
						? VideoUrlImportException.Reason.DATABASE_FAILED
						: hasFfmpegCause(e)
						? VideoUrlImportException.Reason.FFMPEG_FAILED
						: VideoUrlImportException.Reason.SAVE_FAILED;
				String message = reason == VideoUrlImportException.Reason.DATABASE_FAILED
						? "動画ファイルは取得しましたが、ライブラリへ登録できませんでした。"
						: reason == VideoUrlImportException.Reason.FFMPEG_FAILED
						? "動画のMP4変換または互換性調整に失敗しました。"
						: "動画を保存できませんでした。";
				throw new VideoUrlImportException(reason, message, e);
			}
		} catch (VideoUrlImportException e) {
			throw e;
		} catch (IOException e) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.SAVE_FAILED,
					"動画を保存できませんでした。", e);
		} finally {
			deleteRecursively(workDirectory);
		}
	}

	private boolean hasDatabaseCause(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof org.springframework.dao.DataAccessException) return true;
		}
		return false;
	}

	private boolean hasFfmpegCause(Throwable error) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (current instanceof FfmpegProcessRunner.FfmpegTimeoutException) return true;
			String message = current.getMessage();
			if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("ffmpeg")) return true;
		}
		return false;
	}

	private void deleteRecursively(Path directory) {
		if (directory == null || !Files.exists(directory)) return;
		try (var paths = Files.walk(directory)) {
			paths.sorted(Comparator.reverseOrder()).forEach(path -> {
				try { Files.deleteIfExists(path); } catch (IOException ignored) { }
			});
		} catch (IOException ignored) { }
	}
}
