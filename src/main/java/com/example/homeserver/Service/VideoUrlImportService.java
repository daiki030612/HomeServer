package com.example.homeserver.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

@Service
public class VideoUrlImportService {
	private static final Logger logger = LoggerFactory.getLogger(VideoUrlImportService.class);
	private static final String WORK_DIRECTORY_PREFIX = "homeserver-url-import-";

	private final UrlSafetyValidator validator;
	private final List<VideoSourceExtractor> extractors;
	private final SafeUrlHttpClient http;
	private final HlsDownloadService hls;
	private final VideoService videos;
	private final long maxBytes;
	private final Path tempBaseDirectory;

	public VideoUrlImportService(UrlSafetyValidator validator, List<VideoSourceExtractor> extractors,
			SafeUrlHttpClient http, HlsDownloadService hls, VideoService videos,
			@Value("${video.url-import.max-bytes:5368709120}") long maxBytes,
			@Value("${spring.servlet.multipart.location}") String uploadTempLocation) {
		this.validator = validator;
		this.extractors = extractors;
		this.http = http;
		this.hls = hls;
		this.videos = videos;
		this.maxBytes = maxBytes;
		if (uploadTempLocation == null || uploadTempLocation.isBlank()) {
			throw new IllegalArgumentException("spring.servlet.multipart.location must not be blank");
		}
		this.tempBaseDirectory = Path.of(uploadTempLocation).toAbsolutePath().normalize();
	}

	@PostConstruct
	void initializeTempBaseDirectory() {
		try {
			Files.createDirectories(tempBaseDirectory);
			if (!Files.isDirectory(tempBaseDirectory)) {
				throw new IOException("Configured path is not a directory");
			}
			if (!Files.isWritable(tempBaseDirectory)) {
				throw new IOException("Configured directory is not writable");
			}
			Path probe = Files.createTempFile(tempBaseDirectory, ".homeserver-write-test-", ".tmp");
			Files.deleteIfExists(probe);
			logger.info("URL import temporary directory is ready: {}", tempBaseDirectory);
		} catch (IOException | SecurityException e) {
			throw new IllegalStateException(
					"URL import temporary directory is unavailable: " + tempBaseDirectory, e);
		}
	}

	public void importVideo(String rawUrl, Long folderId) {
		importVideo(rawUrl, folderId, VideoUrlImportProgressListener.NOOP);
	}

	public void importVideo(String rawUrl, Long folderId, VideoUrlImportProgressListener progressListener) {
		VideoUrlImportProgressListener progress = progressListener == null
				? VideoUrlImportProgressListener.NOOP : progressListener;
		progress.onStage(VideoUrlImportStage.URL_ANALYZING);
		// Check the configured work filesystem before doing any remote page fetch.
		ensureUsableSpace(VideoSourceExtractor.MediaKind.MP4);
		URI pageUri = validator.validate(rawUrl);
		progress.onStage(VideoUrlImportStage.VIDEO_INFO_FETCHING);
		VideoSourceExtractor.ExtractedVideoSource source = extractSource(pageUri);

		Path workDirectory = null;
		try {
			if (source.kind() == VideoSourceExtractor.MediaKind.HLS) {
				// HLS localization keeps downloaded resources while FFmpeg writes the MP4.
				ensureUsableSpace(VideoSourceExtractor.MediaKind.HLS);
			}
			workDirectory = Files.createTempDirectory(tempBaseDirectory, WORK_DIRECTORY_PREFIX);
			Path downloaded;
			if (source.kind() == VideoSourceExtractor.MediaKind.HLS) {
				progress.onStage(VideoUrlImportStage.HLS_PLAYLIST_ANALYZING);
				downloaded = hls.downloadAsMp4(source.mediaUri(), workDirectory, source.requestContext(), hlsProgress -> {
					progress.onHlsProgress(hlsProgress);
					if (hlsProgress.totalSegments() > 0
							&& hlsProgress.completedSegments() >= hlsProgress.totalSegments()) {
						progress.onStage(VideoUrlImportStage.MP4_CREATING);
					} else {
						progress.onStage(VideoUrlImportStage.DOWNLOADING);
					}
				});
			} else {
				progress.onStage(VideoUrlImportStage.DOWNLOADING);
				downloaded = workDirectory.resolve("downloaded.mp4");
				SafeUrlHttpClient.DownloadResponse response = http.download(
						source.mediaUri(), downloaded, maxBytes, source.requestContext(),
						SafeUrlHttpClient.ImportStage.MEDIA);
				progress.onHlsProgress(new HlsDownloadService.HlsProgress(0, 0, response.bytes()));
			}
			try {
				videos.importDownloadedVideo(downloaded, source.title(), folderId,
						new VideoService.ImportProgressListener() {
							@Override public void onThumbnailGenerating() {
								progress.onStage(VideoUrlImportStage.THUMBNAIL_GENERATING);
							}
							@Override public void onSaving() {
								progress.onStage(VideoUrlImportStage.SAVING);
							}
						});
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

	private void ensureUsableSpace(VideoSourceExtractor.MediaKind mediaKind) {
		try {
			long requiredBytes = mediaKind == VideoSourceExtractor.MediaKind.HLS
					? saturatingMultiply(maxBytes, 2) : maxBytes;
			long usableBytes = Files.getFileStore(tempBaseDirectory).getUsableSpace();
			if (usableBytes < requiredBytes) {
				logger.warn("URL import temporary storage is insufficient: usableBytes={}, requiredBytes={}, kind={}",
						usableBytes, requiredBytes, mediaKind);
				throw new VideoUrlImportException(VideoUrlImportException.Reason.SIZE_LIMIT_EXCEEDED,
						"URLインポート用一時領域の空き容量が不足しています。");
			}
		} catch (IOException | SecurityException e) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.SAVE_FAILED,
					"URLインポート用一時領域の空き容量を確認できませんでした。", e);
		}
	}

	private long saturatingMultiply(long value, int multiplier) {
		if (value <= 0) return 0;
		if (value > Long.MAX_VALUE / multiplier) return Long.MAX_VALUE;
		return value * multiplier;
	}

	private VideoSourceExtractor.ExtractedVideoSource extractSource(URI pageUri) {
		VideoUrlImportException sourceNotFound = null;
		boolean supported = false;
		for (VideoSourceExtractor extractor : extractors) {
			if (!extractor.supports(pageUri)) continue;
			supported = true;
			try {
				return extractor.extract(pageUri);
			} catch (VideoUrlImportException e) {
				if (e.getReason() != VideoUrlImportException.Reason.SOURCE_NOT_FOUND) throw e;
				sourceNotFound = e;
			}
		}
		if (sourceNotFound != null) throw sourceNotFound;
		if (!supported) {
			throw new VideoUrlImportException(VideoUrlImportException.Reason.UNSUPPORTED_SOURCE,
					"この動画ページには対応していません。");
		}
		throw new VideoUrlImportException(VideoUrlImportException.Reason.SOURCE_NOT_FOUND,
				"このページから動画URLを取得できませんでした。");
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
