package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoUrlImportServiceTempDirectoryTests {
	private static final URI PAGE_URI = URI.create("https://video.example/watch/1");
	private static final URI MEDIA_URI = URI.create("https://cdn.example/video.mp4");

	@TempDir
	Path temporaryDirectory;

	@Test
	void createsWorkDirectoryBelowConfiguredUploadTempAndCleansItAfterSuccess() throws Exception {
		Path configuredBase = temporaryDirectory.resolve("upload-temp");
		AtomicReference<Path> observedWorkDirectory = new AtomicReference<>();
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		doAnswer(invocation -> {
			Path destination = invocation.getArgument(1, Path.class);
			observedWorkDirectory.set(destination.getParent());
			Files.write(destination, new byte[] { 1, 2, 3 });
			return new SafeUrlHttpClient.DownloadResponse(MEDIA_URI, "video/mp4", 3);
		}).when(http).download(eq(MEDIA_URI), any(Path.class), anyLong(),
				eq(VideoSourceRequestContext.EMPTY), eq(SafeUrlHttpClient.ImportStage.MEDIA));

		VideoUrlImportService service = service(configuredBase, VideoSourceExtractor.MediaKind.MP4,
				http, mock(HlsDownloadService.class));
		service.initializeTempBaseDirectory();
		service.importVideo(PAGE_URI.toString(), null);

		Path workDirectory = observedWorkDirectory.get();
		assertTrue(workDirectory.getParent().equals(configuredBase.toAbsolutePath().normalize()));
		assertTrue(workDirectory.getFileName().toString().startsWith("homeserver-url-import-"));
		assertFalse(Files.exists(workDirectory));
	}

	@Test
	void preservesExtractorRequestContextForDirectMediaDownload() throws Exception {
		Path configuredBase = temporaryDirectory.resolve("request-context");
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		VideoSourceRequestContext context = new VideoSourceRequestContext("Mozilla/5.0 Fixture",
				URI.create("https://www.tokyomotion.net/"), true, true, true);
		doAnswer(invocation -> {
			Path destination = invocation.getArgument(1, Path.class);
			Files.write(destination, new byte[] { 1 });
			return new SafeUrlHttpClient.DownloadResponse(MEDIA_URI, "video/mp4", 1);
		}).when(http).download(eq(MEDIA_URI), any(Path.class), anyLong(), eq(context),
				eq(SafeUrlHttpClient.ImportStage.MEDIA));
		VideoUrlImportService service = service(configuredBase, VideoSourceExtractor.MediaKind.MP4,
				http, mock(HlsDownloadService.class), mock(VideoService.class), context);
		service.initializeTempBaseDirectory();

		service.importVideo(PAGE_URI.toString(), null);

		verify(http).download(eq(MEDIA_URI), any(Path.class), eq(1024L), eq(context),
				eq(SafeUrlHttpClient.ImportStage.MEDIA));
	}

	@Test
	void createsConfiguredBaseDirectoryWhenItDoesNotExist() {
		Path configuredBase = temporaryDirectory.resolve("missing/child/upload-temp");
		VideoUrlImportService service = service(configuredBase, VideoSourceExtractor.MediaKind.MP4,
				mock(SafeUrlHttpClient.class), mock(HlsDownloadService.class));

		service.initializeTempBaseDirectory();

		assertTrue(Files.isDirectory(configuredBase));
	}

	@Test
	void rejectsConfiguredBaseThatCannotBeUsedAsDirectory() throws Exception {
		Path configuredBase = temporaryDirectory.resolve("not-a-directory");
		Files.writeString(configuredBase, "file");
		VideoUrlImportService service = service(configuredBase, VideoSourceExtractor.MediaKind.MP4,
				mock(SafeUrlHttpClient.class), mock(HlsDownloadService.class));

		assertThrows(IllegalStateException.class, service::initializeTempBaseDirectory);
	}

	@Test
	void cleansWorkDirectoryAfterHlsFailure() {
		assertHlsFailureCleans(VideoUrlImportException.Reason.HLS_DOWNLOAD_FAILED);
	}

	@Test
	void cleansWorkDirectoryAfterFfmpegFailure() {
		assertHlsFailureCleans(VideoUrlImportException.Reason.FFMPEG_FAILED);
	}

	@Test
	void connectsHlsAndLibraryProgressStages() throws Exception {
		Path configuredBase = temporaryDirectory.resolve("progress");
		HlsDownloadService hls = mock(HlsDownloadService.class);
		doAnswer(invocation -> {
			Path workDirectory = invocation.getArgument(1, Path.class);
			HlsDownloadService.ProgressListener listener = invocation.getArgument(3);
			listener.onProgress(new HlsDownloadService.HlsProgress(10, 4, 2048));
			listener.onProgress(new HlsDownloadService.HlsProgress(10, 10, 4096));
			Path output = workDirectory.resolve("downloaded.mp4");
			Files.write(output, new byte[] { 1 });
			return output;
		}).when(hls).downloadAsMp4(eq(MEDIA_URI), any(Path.class),
				eq(VideoSourceRequestContext.EMPTY), any());
		VideoService videos = mock(VideoService.class);
		doAnswer(invocation -> {
			VideoService.ImportProgressListener listener = invocation.getArgument(3);
			listener.onThumbnailGenerating();
			listener.onSaving();
			return null;
		}).when(videos).importDownloadedVideo(any(Path.class), eq("title"), eq(null), any());
		VideoUrlImportService service = service(configuredBase, VideoSourceExtractor.MediaKind.HLS,
				mock(SafeUrlHttpClient.class), hls, videos);
		service.initializeTempBaseDirectory();
		List<VideoUrlImportStage> stages = new CopyOnWriteArrayList<>();
		List<HlsDownloadService.HlsProgress> snapshots = new CopyOnWriteArrayList<>();

		service.importVideo(PAGE_URI.toString(), null, new VideoUrlImportProgressListener() {
			@Override public void onStage(VideoUrlImportStage stage) { stages.add(stage); }
			@Override public void onHlsProgress(HlsDownloadService.HlsProgress progress) {
				snapshots.add(progress);
			}
		});

		assertTrue(stages.containsAll(List.of(VideoUrlImportStage.URL_ANALYZING,
				VideoUrlImportStage.VIDEO_INFO_FETCHING, VideoUrlImportStage.HLS_PLAYLIST_ANALYZING,
				VideoUrlImportStage.DOWNLOADING, VideoUrlImportStage.MP4_CREATING,
				VideoUrlImportStage.THUMBNAIL_GENERATING, VideoUrlImportStage.SAVING)));
		assertTrue(snapshots.stream().anyMatch(progress -> progress.completedSegments() == 4));
	}

	private void assertHlsFailureCleans(VideoUrlImportException.Reason reason) {
		Path configuredBase = temporaryDirectory.resolve(reason.name());
		AtomicReference<Path> observedWorkDirectory = new AtomicReference<>();
		HlsDownloadService hls = mock(HlsDownloadService.class);
		when(hls.downloadAsMp4(eq(MEDIA_URI), any(Path.class), eq(VideoSourceRequestContext.EMPTY), any()))
				.thenAnswer(invocation -> {
					Path workDirectory = invocation.getArgument(1, Path.class);
					observedWorkDirectory.set(workDirectory);
					Files.write(workDirectory.resolve("partial.ts"), new byte[] { 1 });
					throw new VideoUrlImportException(reason, "safe failure");
				});
		VideoUrlImportService service = service(configuredBase, VideoSourceExtractor.MediaKind.HLS,
				mock(SafeUrlHttpClient.class), hls);
		service.initializeTempBaseDirectory();

		assertThrows(VideoUrlImportException.class, () -> service.importVideo(PAGE_URI.toString(), null));
		assertFalse(Files.exists(observedWorkDirectory.get()));
	}

	private VideoUrlImportService service(Path configuredBase, VideoSourceExtractor.MediaKind kind,
			SafeUrlHttpClient http, HlsDownloadService hls) {
		return service(configuredBase, kind, http, hls, mock(VideoService.class));
	}

	private VideoUrlImportService service(Path configuredBase, VideoSourceExtractor.MediaKind kind,
			SafeUrlHttpClient http, HlsDownloadService hls, VideoService videos) {
		return service(configuredBase, kind, http, hls, videos, VideoSourceRequestContext.EMPTY);
	}

	private VideoUrlImportService service(Path configuredBase, VideoSourceExtractor.MediaKind kind,
			SafeUrlHttpClient http, HlsDownloadService hls, VideoService videos,
			VideoSourceRequestContext requestContext) {
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		when(validator.validate(PAGE_URI.toString())).thenReturn(PAGE_URI);
		VideoSourceExtractor extractor = mock(VideoSourceExtractor.class);
		when(extractor.supports(PAGE_URI)).thenReturn(true);
		when(extractor.extract(PAGE_URI)).thenReturn(
				new VideoSourceExtractor.ExtractedVideoSource("title", MEDIA_URI, kind, requestContext));
		return new VideoUrlImportService(validator, List.of(extractor), http, hls,
				videos, 1024, configuredBase.toString());
	}
}
