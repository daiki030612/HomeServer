package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
		URI segment = URI.create("https://media.example/public/segment-1.ts");
		when(http.getText(playlist, 1000)).thenReturn(new SafeUrlHttpClient.TextResponse(
				playlist, "#EXTM3U\n#EXTINF:5,\nsegment-1.ts\n#EXT-X-ENDLIST\n",
				"application/vnd.apple.mpegurl"));
		doAnswer(invocation -> {
			Path target = invocation.getArgument(1, Path.class);
			Files.write(target, new byte[] { 1, 2, 3 });
			return new SafeUrlHttpClient.DownloadResponse(segment, "video/mp2t", 3);
		}).when(http).download(any(URI.class), any(Path.class), anyLong());
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			List<String> command = invocation.getArgument(0, List.class);
			Files.write(Path.of(command.getLast()), new byte[] { 9 });
			return new FfmpegProcessRunner.ProcessResult(0, "");
		}).when(runner).run(any(), any(Duration.class));

		Path result = new HlsDownloadService(http, runner, 1000, Duration.ofMinutes(1))
				.downloadAsMp4(playlist, directory);

		assertTrue(Files.isRegularFile(result));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
		verify(runner).run(command.capture(), any(Duration.class));
		assertTrue(command.getValue().stream().noneMatch(value -> value.startsWith("https://")));
		assertTrue(command.getValue().contains("file,crypto,data"));
	}
}
