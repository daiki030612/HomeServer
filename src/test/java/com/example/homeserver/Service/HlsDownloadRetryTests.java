package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HlsDownloadRetryTests {
	private static final URI PLAYLIST = URI.create("https://media.example/public/index.m3u8?token=secret");
	private static final URI SEGMENT = URI.create("https://media.example/public/video0.ts?token=secret");
	private static final VideoSourceRequestContext CONTEXT = new VideoSourceRequestContext(
			"Test Browser/1.0", URI.create("https://missav.live/ja/test"));

	@TempDir
	Path directory;

	@Test
	void retries503ThenSucceedsWithSameHeaderContext() throws Exception {
		SafeUrlHttpClient http = preparedHttp();
		AtomicInteger attempts = new AtomicInteger();
		doAnswer(invocation -> {
			if (attempts.incrementAndGet() == 1) throw statusFailure(503, null);
			return writeDownloaded(invocation.getArgument(1, Path.class));
		}).when(http).download(eq(SEGMENT), any(Path.class), anyLong(), eq(CONTEXT),
				eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE), any());

		service(http, millis -> { }).downloadAsMp4(PLAYLIST, directory, CONTEXT);

		verify(http, times(2)).download(eq(SEGMENT), any(Path.class), anyLong(), eq(CONTEXT),
				eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE), any());
	}

	@Test
	void retries429AndCapsRetryAfter() throws Exception {
		SafeUrlHttpClient http = preparedHttp();
		AtomicInteger attempts = new AtomicInteger();
		List<Long> delays = new CopyOnWriteArrayList<>();
		doAnswer(invocation -> {
			if (attempts.incrementAndGet() == 1) throw statusFailure(429, Duration.ofMinutes(10));
			return writeDownloaded(invocation.getArgument(1, Path.class));
		}).when(http).download(eq(SEGMENT), any(Path.class), anyLong(), eq(CONTEXT),
				eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE), any());

		service(http, delays::add).downloadAsMp4(PLAYLIST, directory, CONTEXT);

		assertEquals(List.of(5_000L), delays);
		assertEquals(2, attempts.get());
	}

	@Test
	void retriesConnectionResetThenSucceeds() throws Exception {
		SafeUrlHttpClient http = preparedHttp();
		AtomicInteger attempts = new AtomicInteger();
		doAnswer(invocation -> {
			if (attempts.incrementAndGet() == 1) throw networkFailure(new IOException("Connection reset"));
			return writeDownloaded(invocation.getArgument(1, Path.class));
		}).when(http).download(eq(SEGMENT), any(Path.class), anyLong(), eq(CONTEXT),
				eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE), any());

		service(http, millis -> { }).downloadAsMp4(PLAYLIST, directory, CONTEXT);
		assertEquals(2, attempts.get());
	}

	@Test
	void retryLimitBecomesHlsDownloadFailed() {
		SafeUrlHttpClient http = preparedHttp();
		when(http.download(eq(SEGMENT), any(Path.class), anyLong(), eq(CONTEXT),
				eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE), any()))
				.thenThrow(statusFailure(503, null));

		VideoUrlImportException error = assertThrows(VideoUrlImportException.class,
				() -> service(http, millis -> { }).downloadAsMp4(PLAYLIST, directory, CONTEXT));

		assertEquals(VideoUrlImportException.Reason.HLS_DOWNLOAD_FAILED, error.getReason());
		verify(http, times(4)).download(eq(SEGMENT), any(Path.class), anyLong(), eq(CONTEXT),
				eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE), any());
	}

	@Test
	void doesNotRetryPermanent404SsrfSizeLimitOrInterrupted() {
		assertNotRetried(statusFailure(404, null));
		assertNotRetried(new VideoUrlImportException(VideoUrlImportException.Reason.INVALID_URL, "safe"));
		assertNotRetried(new VideoUrlImportException(VideoUrlImportException.Reason.SIZE_LIMIT_EXCEEDED, "safe"));
		assertNotRetried(networkFailure(new IOException("interrupted", new InterruptedException())));
	}

	@Test
	void firstParallelRootCauseIsNotReplacedByCancellation() {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		when(http.getText(PLAYLIST, 10_000, CONTEXT, SafeUrlHttpClient.ImportStage.HLS_PLAYLIST))
				.thenReturn(new SafeUrlHttpClient.TextResponse(PLAYLIST,
						"#EXTM3U\n#EXTINF:4,\nvideo0.ts?token=secret\n#EXTINF:4,\nvideo1.ts\n",
						"application/vnd.apple.mpegurl"));
		IllegalStateException original = new IllegalStateException("original segment failure");
		doAnswer(invocation -> {
			URI uri = invocation.getArgument(0, URI.class);
			if (uri.getPath().endsWith("video0.ts")) throw original;
			try {
				Thread.sleep(2_000);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw networkFailure(interrupted);
			}
			return writeDownloaded(invocation.getArgument(1, Path.class));
		}).when(http).download(any(URI.class), any(Path.class), anyLong(), eq(CONTEXT),
				eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE), any());

		VideoUrlImportException error = assertThrows(VideoUrlImportException.class,
				() -> service(http, millis -> { }).downloadAsMp4(PLAYLIST, directory, CONTEXT));

		assertEquals(VideoUrlImportException.Reason.HLS_DOWNLOAD_FAILED, error.getReason());
		assertNotNull(findCause(error, IllegalStateException.class));
	}

	private void assertNotRetried(RuntimeException failure) {
		SafeUrlHttpClient http = preparedHttp();
		when(http.download(eq(SEGMENT), any(Path.class), anyLong(), eq(CONTEXT),
				eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE), any())).thenThrow(failure);

		assertThrows(VideoUrlImportException.class,
				() -> service(http, millis -> { }).downloadAsMp4(PLAYLIST, directory, CONTEXT));
		verify(http, times(1)).download(eq(SEGMENT), any(Path.class), anyLong(), eq(CONTEXT),
				eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE), any());
		Thread.interrupted();
	}

	private SafeUrlHttpClient preparedHttp() {
		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		when(http.getText(PLAYLIST, 10_000, CONTEXT, SafeUrlHttpClient.ImportStage.HLS_PLAYLIST))
				.thenReturn(new SafeUrlHttpClient.TextResponse(PLAYLIST,
						"#EXTM3U\n#EXTINF:4,\nvideo0.ts?token=secret\n", "application/vnd.apple.mpegurl"));
		return http;
	}

	private HlsDownloadService service(SafeUrlHttpClient http, HlsDownloadService.RetrySleeper sleeper) {
		FfmpegProcessRunner runner = mock(FfmpegProcessRunner.class);
		try {
			doAnswer(invocation -> {
				@SuppressWarnings("unchecked")
				List<String> command = invocation.getArgument(0, List.class);
				Files.write(Path.of(command.getLast()), new byte[] { 1 });
				return new FfmpegProcessRunner.ProcessResult(0, "");
			}).when(runner).run(any(), any(Duration.class));
		} catch (Exception impossible) {
			throw new AssertionError(impossible);
		}
		return new HlsDownloadService(http, runner, 10_000, Duration.ofMinutes(1), 2, sleeper);
	}

	private SafeUrlHttpClient.DownloadResponse writeDownloaded(Path target) throws IOException {
		Files.write(target, new byte[] { 1, 2, 3 });
		return new SafeUrlHttpClient.DownloadResponse(SEGMENT, "video/mp2t", 3);
	}

	private VideoUrlImportException statusFailure(int status, Duration retryAfter) {
		return networkFailure(new SafeUrlHttpClient.HttpStatusException(status, retryAfter));
	}

	private VideoUrlImportException networkFailure(Throwable cause) {
		return new VideoUrlImportException(VideoUrlImportException.Reason.MEDIA_DOWNLOAD_FAILED,
				"safe download failure", cause);
	}

	private <T extends Throwable> T findCause(Throwable error, Class<T> type) {
		for (Throwable current = error; current != null; current = current.getCause()) {
			if (type.isInstance(current)) return type.cast(current);
		}
		return null;
	}
}
