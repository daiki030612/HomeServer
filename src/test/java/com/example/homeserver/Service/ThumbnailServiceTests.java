package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThumbnailServiceTests {

	@TempDir
	Path temporaryDirectory;

	@Test
	void nonZeroFfmpegExitIsReportedAndTemporaryOutputIsRemoved() throws Exception {
		ThumbnailService service = new StubThumbnailService(1, new byte[] { 1 });
		Path output = temporaryDirectory.resolve("thumbnail.jpg");

		assertThrows(IllegalStateException.class,
				() -> service.createThumbnail("video.mp4", output.toString()));

		assertFalse(Files.exists(output));
		assertNoTemporaryThumbnail();
	}

	@Test
	void missingThumbnailIsReportedAsFailure() throws Exception {
		ThumbnailService service = new StubThumbnailService(0, null);
		Path output = temporaryDirectory.resolve("thumbnail.jpg");

		assertThrows(IllegalStateException.class,
				() -> service.createThumbnail("video.mp4", output.toString()));

		assertFalse(Files.exists(output));
		assertNoTemporaryThumbnail();
	}

	@Test
	void emptyThumbnailIsReportedAndRemoved() throws Exception {
		ThumbnailService service = new StubThumbnailService(0, new byte[0]);
		Path output = temporaryDirectory.resolve("thumbnail.jpg");

		assertThrows(IllegalStateException.class,
				() -> service.createThumbnail("video.mp4", output.toString()));

		assertFalse(Files.exists(output));
		assertNoTemporaryThumbnail();
	}

	@Test
	void validThumbnailIsMovedToFinalOutput() throws Exception {
		ThumbnailService service = new StubThumbnailService(0, new byte[] { 1, 2, 3 });
		Path output = temporaryDirectory.resolve("thumbnail.jpg");

		String result = service.createThumbnail("video.mp4", output.toString());

		assertEquals(output.toAbsolutePath().normalize().toString(), result);
		assertEquals(3, Files.size(output));
		assertNoTemporaryThumbnail();
	}

	private void assertNoTemporaryThumbnail() throws Exception {
		try (var files = Files.list(temporaryDirectory)) {
			assertFalse(files.anyMatch(path -> path.getFileName().toString().contains(".tmp.jpg")));
		}
	}

	private static class StubThumbnailService extends ThumbnailService {

		private final int exitCode;
		private final byte[] generatedContent;

		StubThumbnailService(int exitCode, byte[] generatedContent) {
			this.exitCode = exitCode;
			this.generatedContent = generatedContent;
		}

		@Override
		protected Process startProcess(ProcessBuilder builder) throws IOException {
			if (generatedContent != null) {
				Path output = Path.of(builder.command().get(builder.command().size() - 1));
				Files.write(output, generatedContent);
			}

			Process process = mock(Process.class);
			try {
				when(process.waitFor()).thenReturn(exitCode);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
			return process;
		}
	}
}
