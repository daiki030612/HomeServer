package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

	private void assertHlsFailureCleans(VideoUrlImportException.Reason reason) {
		Path configuredBase = temporaryDirectory.resolve(reason.name());
		AtomicReference<Path> observedWorkDirectory = new AtomicReference<>();
		HlsDownloadService hls = mock(HlsDownloadService.class);
		when(hls.downloadAsMp4(eq(MEDIA_URI), any(Path.class), eq(VideoSourceRequestContext.EMPTY)))
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
		UrlSafetyValidator validator = mock(UrlSafetyValidator.class);
		when(validator.validate(PAGE_URI.toString())).thenReturn(PAGE_URI);
		VideoSourceExtractor extractor = mock(VideoSourceExtractor.class);
		when(extractor.supports(PAGE_URI)).thenReturn(true);
		when(extractor.extract(PAGE_URI)).thenReturn(
				new VideoSourceExtractor.ExtractedVideoSource("title", MEDIA_URI, kind));
		return new VideoUrlImportService(validator, List.of(extractor), http, hls,
				mock(VideoService.class), 1024, configuredBase.toString());
	}
}
