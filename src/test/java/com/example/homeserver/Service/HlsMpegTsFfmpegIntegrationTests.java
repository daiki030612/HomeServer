package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HlsMpegTsFfmpegIntegrationTests {
	@TempDir
	Path directory;

	@Test
	void convertsMpegTsServedWithJpegNameToMp4WithoutReencoding() throws Exception {
		assumeTrue(ffmpegAvailable(), "FFmpeg is not installed");
		Path fixture = directory.resolve("remote-video0.jpeg");
		Process fixtureProcess = new ProcessBuilder(List.of(
				"ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
				"-f", "lavfi", "-i", "testsrc=size=160x90:rate=25",
				"-f", "lavfi", "-i", "sine=frequency=1000:sample_rate=48000",
				"-t", "0.5", "-c:v", "libx264", "-pix_fmt", "yuv420p",
				"-c:a", "aac", "-f", "mpegts", fixture.toString())).start();
		assertTrue(fixtureProcess.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
		assertTrue(fixtureProcess.exitValue() == 0 && Files.size(fixture) > 0);

		SafeUrlHttpClient http = mock(SafeUrlHttpClient.class);
		URI playlist = URI.create("https://media.example/720p/video.m3u8");
		URI segment = URI.create("https://media.example/720p/video0.jpeg");
		when(http.getText(playlist, 2L * 1024 * 1024, VideoSourceRequestContext.EMPTY,
				SafeUrlHttpClient.ImportStage.HLS_PLAYLIST)).thenReturn(new SafeUrlHttpClient.TextResponse(
				playlist, "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:1\n"
				+ "#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:0.500000,\nvideo0.jpeg\n#EXT-X-ENDLIST\n",
				"application/vnd.apple.mpegurl"));
		doAnswer(invocation -> {
			Path target = invocation.getArgument(1, Path.class);
			Files.copy(fixture, target, StandardCopyOption.REPLACE_EXISTING);
			return new SafeUrlHttpClient.DownloadResponse(segment, "image/jpeg", Files.size(target));
		}).when(http).download(eq(segment), any(Path.class), anyLong(),
				eq(VideoSourceRequestContext.EMPTY), eq(SafeUrlHttpClient.ImportStage.HLS_RESOURCE),
				any(SafeUrlHttpClient.SharedDownloadBudget.class));

		Path result = new HlsDownloadService(http, new FfmpegProcessRunner(), 10_000_000,
				Duration.ofMinutes(1), 2).downloadAsMp4(playlist, directory);

		assertTrue(Files.isRegularFile(result));
		assertTrue(Files.size(result) > 0);
		assertTrue(Files.readString(directory.resolve("media-0.m3u8")).contains("resource-00000.ts"));
	}

	private boolean ffmpegAvailable() {
		try {
			Process process = new ProcessBuilder("ffmpeg", "-version").start();
			return process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0;
		} catch (Exception ignored) {
			return false;
		}
	}
}
