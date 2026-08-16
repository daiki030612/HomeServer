package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class HlsDownloadServiceTests {
	@TempDir
	Path directory;

	@Test
	void downloadsValidatedResourcesAndGivesFfmpegOnlyLocalPlaylist() throws Exception {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		FfmpegProcessRunner runner = mock(FfmpegProcessRunner.class);
		URI playlist = URI.create("https://media.example/public/index.m3u8");
		URI variant = URI.create("https://media.example/public/720p.m3u8");
		URI segment = URI.create("https://media.example/public/segment-1.jpeg");
		URI key = URI.create("https://media.example/public/key.bin");
		URI map = URI.create("https://media.example/public/init.mp4");
		VideoSourceRequestContext context = new VideoSourceRequestContext(
				"Test Browser/1.0", URI.create("https://missav.live/ja/test"));
		when(http.getText(playlist, 1000, context, SafeUrlHttpClient.ImportStage.HLS_PLAYLIST))
				.thenReturn(new SafeUrlHttpClient.TextResponse(
				playlist, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\n720p.m3u8\n",
				"application/vnd.apple.mpegurl"));
		when(http.getText(variant, 1000, context, SafeUrlHttpClient.ImportStage.HLS_PLAYLIST))
				.thenReturn(new SafeUrlHttpClient.TextResponse(
				variant, "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
				+ "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:5,\nsegment-1.jpeg\n#EXT-X-ENDLIST\n",
				"application/vnd.apple.mpegurl"));
		doAnswer(invocation -> {
			URI requested = invocation.getArgument(0, URI.class);
			Path target = invocation.getArgument(1, Path.class);
			Files.write(target, new byte[] { 1, 2, 3 });
			return new SafeUrlHttpClient.DownloadResponse(requested, "application/octet-stream", 3);
		}).when(http).download(any(URI.class), any(Path.class), anyLong(), eq(context),
				eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE),
				any(SafeUrlHttpClient.SharedDownloadBudget.class));
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			List<String> command = invocation.getArgument(0, List.class);
			Files.write(Path.of(command.getLast()), new byte[] { 9 });
			return new FfmpegProcessRunner.ProcessResult(0, "");
		}).when(runner).run(any(), any(Duration.class));

		Path result = new HlsDownloadService(http, runner, 1000, Duration.ofMinutes(1), 4)
				.downloadAsMp4(playlist, directory, context);

		assertTrue(Files.isRegularFile(result));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
		verify(runner).run(command.capture(), any(Duration.class));
		assertTrue(command.getValue().stream().noneMatch(value -> value.startsWith("https://")));
		assertTrue(command.getValue().contains("file,crypto,data"));
		String localized = Files.readString(directory.resolve("media-1.m3u8"));
		assertTrue(localized.contains("resource-00002.ts"));
		assertFalse(localized.contains("resource-00002.jpeg"));
		assertTrue(localized.contains("resource-00000.bin"));
		assertTrue(localized.contains("resource-00001.mp4"));
		verify(http).getText(playlist, 1000, context, SafeUrlHttpClient.ImportStage.HLS_PLAYLIST);
		verify(http).getText(variant, 1000, context, SafeUrlHttpClient.ImportStage.HLS_PLAYLIST);
		verify(http).download(eq(segment), any(Path.class), anyLong(), eq(context),
				eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE),
				any(SafeUrlHttpClient.SharedDownloadBudget.class));
		verify(http).download(eq(key), any(Path.class), anyLong(), eq(context),
				eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE),
				any(SafeUrlHttpClient.SharedDownloadBudget.class));
		verify(http).download(eq(map), any(Path.class), anyLong(), eq(context),
				eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE),
				any(SafeUrlHttpClient.SharedDownloadBudget.class));
	}

	@Test
	void headerFreeHlsKeepsLegacyEmptyContextBehavior() throws Exception {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		FfmpegProcessRunner runner = mock(FfmpegProcessRunner.class);
		URI playlist = URI.create("https://ordinary.example/index.m3u8");
		when(http.getText(playlist, 1000, VideoSourceRequestContext.EMPTY,
				SafeUrlHttpClient.ImportStage.HLS_PLAYLIST)).thenReturn(new SafeUrlHttpClient.TextResponse(
				playlist, "#EXTM3U\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl"));
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			List<String> command = invocation.getArgument(0, List.class);
			Files.write(Path.of(command.getLast()), new byte[] { 9 });
			return new FfmpegProcessRunner.ProcessResult(0, "");
		}).when(runner).run(any(), any(Duration.class));

		new HlsDownloadService(http, runner, 1000, Duration.ofMinutes(1), 4)
				.downloadAsMp4(playlist, directory);

		verify(http).getText(playlist, 1000, VideoSourceRequestContext.EMPTY,
				SafeUrlHttpClient.ImportStage.HLS_PLAYLIST);
	}

	@Test
	void downloadsSegmentsInParallelWithinLimitAndKeepsPlaylistOrderAndDeduplication() throws Exception {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		FfmpegProcessRunner runner = successfulRunner();
		URI playlist = URI.create("https://media.example/parallel/index.m3u8");
		URI referer = URI.create("https://missav.live/ja/parallel");
		VideoSourceRequestContext context = new VideoSourceRequestContext("Test Browser/1.0", referer);
		StringBuilder media = new StringBuilder("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n"
				+ "#EXT-X-MAP:URI=\"init.mp4\"\n");
		for (int i = 0; i < 6; i++) {
			media.append("#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXTINF:4,\nvideo")
					.append(i).append(".jpeg\n");
		}
		when(http.getText(playlist, 10_000, context, SafeUrlHttpClient.ImportStage.HLS_PLAYLIST))
				.thenReturn(new SafeUrlHttpClient.TextResponse(playlist, media.toString(),
						"application/vnd.apple.mpegurl"));
		AtomicInteger active = new AtomicInteger();
		AtomicInteger maximum = new AtomicInteger();
		doAnswer(invocation -> {
			int current = active.incrementAndGet();
			maximum.accumulateAndGet(current, Math::max);
			try {
				Thread.sleep(60);
				Path target = invocation.getArgument(1, Path.class);
				Files.write(target, new byte[] { 1 });
				return new SafeUrlHttpClient.DownloadResponse(invocation.getArgument(0, URI.class),
						"application/octet-stream", 1);
			} finally {
				active.decrementAndGet();
			}
		}).when(http).download(any(URI.class), any(Path.class), anyLong(), eq(context),
				eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE),
				any(SafeUrlHttpClient.SharedDownloadBudget.class));
		List<HlsDownloadService.HlsProgress> progress = new CopyOnWriteArrayList<>();

		new HlsDownloadService(http, runner, 10_000, Duration.ofMinutes(1), 3)
				.downloadAsMp4(playlist, directory, context, progress::add);

		assertTrue(maximum.get() > 1);
		assertTrue(maximum.get() <= 3);
		String localized = Files.readString(directory.resolve("media-0.m3u8"));
		int previous = -1;
		for (int i = 0; i < 6; i++) {
			int position = localized.indexOf(String.format("resource-%05d.ts", i + 2));
			assertTrue(position > previous);
			previous = position;
		}
		verify(http, times(1)).download(eq(URI.create("https://media.example/parallel/key.bin")),
				any(Path.class), anyLong(), eq(context), eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE),
				any(SafeUrlHttpClient.SharedDownloadBudget.class));
		verify(http, times(1)).download(eq(URI.create("https://media.example/parallel/init.mp4")),
				any(Path.class), anyLong(), eq(context), eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE),
				any(SafeUrlHttpClient.SharedDownloadBudget.class));
		HlsDownloadService.HlsProgress last = progress.stream()
				.max(java.util.Comparator.comparingInt(HlsDownloadService.HlsProgress::completedSegments)).orElseThrow();
		assertEquals(6, last.totalSegments());
		assertEquals(6, last.completedSegments());
	}

	@Test
	void oneSegmentFailureCancelsImportAndSkipsFfmpeg() {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		FfmpegProcessRunner runner = mock(FfmpegProcessRunner.class);
		URI playlist = URI.create("https://media.example/failure/index.m3u8");
		StringBuilder media = new StringBuilder("#EXTM3U\n");
		for (int i = 0; i < 20; i++) media.append("#EXTINF:4,\nvideo").append(i).append(".ts\n");
		when(http.getText(playlist, 10_000, VideoSourceRequestContext.EMPTY,
				SafeUrlHttpClient.ImportStage.HLS_PLAYLIST)).thenReturn(new SafeUrlHttpClient.TextResponse(
				playlist, media.toString(), "application/vnd.apple.mpegurl"));
		AtomicInteger started = new AtomicInteger();
		doAnswer(invocation -> {
			started.incrementAndGet();
			URI uri = invocation.getArgument(0, URI.class);
			if (uri.getPath().endsWith("video0.ts")) throw new IllegalStateException("segment failure");
			try { Thread.sleep(5_000); } catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("cancelled", e);
			}
			return null;
		}).when(http).download(any(URI.class), any(Path.class), anyLong(),
				eq(VideoSourceRequestContext.EMPTY), eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE),
				any(SafeUrlHttpClient.SharedDownloadBudget.class));

		VideoUrlImportException error = assertThrows(VideoUrlImportException.class,
				() -> new HlsDownloadService(http, runner, 10_000, Duration.ofSeconds(10), 2)
						.downloadAsMp4(playlist, directory));

		assertEquals(VideoUrlImportException.Reason.HLS_DOWNLOAD_FAILED, error.getReason());
		assertTrue(started.get() < 20);
		verify(runner, never()).run(any(), any(Duration.class));
	}

	@Test
	void choosesHighestResolutionVariant() throws Exception {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		FfmpegProcessRunner runner = successfulRunner();
		URI master = URI.create("https://media.example/master.m3u8");
		URI highest = URI.create("https://media.example/720.m3u8");
		when(http.getText(master, 1000, VideoSourceRequestContext.EMPTY,
				SafeUrlHttpClient.ImportStage.HLS_PLAYLIST)).thenReturn(new SafeUrlHttpClient.TextResponse(master,
				"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=640x360\n360.m3u8\n"
				+ "#EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=1280x720\n720.m3u8\n"
				+ "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480\n480.m3u8\n",
				"application/vnd.apple.mpegurl"));
		when(http.getText(highest, 1000, VideoSourceRequestContext.EMPTY,
				SafeUrlHttpClient.ImportStage.HLS_PLAYLIST)).thenReturn(new SafeUrlHttpClient.TextResponse(
				highest, "#EXTM3U\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl"));

		new HlsDownloadService(http, runner, 1000, Duration.ofMinutes(1), 2)
				.downloadAsMp4(master, directory);

		verify(http).getText(highest, 1000, VideoSourceRequestContext.EMPTY,
				SafeUrlHttpClient.ImportStage.HLS_PLAYLIST);
		verify(http, never()).getText(eq(URI.create("https://media.example/360.m3u8")), anyLong(),
				any(VideoSourceRequestContext.class), any(SafeUrlHttpClient.ImportStage.class));
	}

	private FfmpegProcessRunner successfulRunner() throws Exception {
		FfmpegProcessRunner runner = mock(FfmpegProcessRunner.class);
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			List<String> command = invocation.getArgument(0, List.class);
			Files.write(Path.of(command.getLast()), new byte[] { 9 });
			return new FfmpegProcessRunner.ProcessResult(0, "");
		}).when(runner).run(any(), any(Duration.class));
		return runner;
	}
}
