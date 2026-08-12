package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class VideoRemuxServiceTests {

	@TempDir
	Path temporaryDirectory;

	@Test
	void usesStreamCopyFastStartAndExplicitSelectedVideoAndFirstAudio() throws Exception {
		Path source = Files.write(temporaryDirectory.resolve("input.mov"), new byte[] { 1 });
		Path output = temporaryDirectory.resolve("output.mp4");
		FfmpegProcessRunner runner = mock(FfmpegProcessRunner.class);
		when(runner.run(any(), any())).thenAnswer(invocation -> {
			Files.write(output, new byte[] { 2 });
			return new FfmpegProcessRunner.ProcessResult(0, "");
		});

		new VideoRemuxService(runner).remux(source, output, temporaryDirectory, 2);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
		verify(runner).run(command.capture(), any());
		List<String> arguments = command.getValue();
		assertTrue(arguments.containsAll(List.of(
				"-map", "0:2", "0:a:0?", "-c", "copy", "-movflags", "+faststart")));
	}

	@Test
	void rejectsOutputOutsideStorage() throws Exception {
		Path storage = Files.createDirectory(temporaryDirectory.resolve("storage"));
		Path source = Files.write(storage.resolve("input.mov"), new byte[] { 1 });
		Path outside = temporaryDirectory.resolve("outside.mp4");

		assertThrows(IllegalArgumentException.class,
				() -> new VideoRemuxService(mock(FfmpegProcessRunner.class))
						.remux(source, outside, storage, 0));
	}

	@Test
	void processRunnerTerminatesTimedOutFfmpeg() throws Exception {
		Process process = mock(Process.class);
		when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
		when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
		when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
		when(process.waitFor()).thenReturn(0);

		FfmpegProcessRunner runner = new FfmpegProcessRunner() {
			@Override
			protected Process start(List<String> command) {
				return process;
			}
		};

		assertThrows(FfmpegProcessRunner.FfmpegTimeoutException.class,
				() -> runner.run(List.of("ffmpeg"), Duration.ofMillis(1)));
		verify(process).destroyForcibly();
	}
}
