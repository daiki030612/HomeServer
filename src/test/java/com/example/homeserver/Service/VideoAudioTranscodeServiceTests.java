package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class VideoAudioTranscodeServiceTests {

	@TempDir
	Path temporaryDirectory;

	@Test
	void copiesVideoAndConvertsFirstAudioToAacLc48kStereo() throws Exception {
		Path source = Files.write(temporaryDirectory.resolve("001.MP4"), new byte[] { 1 });
		Path output = temporaryDirectory.resolve("output.mp4");
		FfmpegProcessRunner runner = mock(FfmpegProcessRunner.class);
		when(runner.run(any(), any())).thenAnswer(invocation -> {
			Files.write(output, new byte[] { 2 });
			return new FfmpegProcessRunner.ProcessResult(0, "");
		});

		new VideoAudioTranscodeService(runner)
				.transcodeAudio(source, output, temporaryDirectory);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
		verify(runner).run(command.capture(), any());
		List<String> arguments = command.getValue();
		assertTrue(arguments.containsAll(List.of(
				"-map", "0:v:0", "0:a:0",
				"-c:v", "copy", "-c:a", "aac",
				"-profile:a", "aac_low", "-ar", "48000", "-ac", "2",
				"-movflags", "+faststart")));
	}

	@Test
	void rejectsOutputOutsideStorage() throws Exception {
		Path storage = Files.createDirectory(temporaryDirectory.resolve("storage"));
		Path source = Files.write(storage.resolve("001.MP4"), new byte[] { 1 });

		assertThrows(IllegalArgumentException.class,
				() -> new VideoAudioTranscodeService(mock(FfmpegProcessRunner.class))
						.transcodeAudio(source, temporaryDirectory.resolve("outside.mp4"), storage));
	}
}
