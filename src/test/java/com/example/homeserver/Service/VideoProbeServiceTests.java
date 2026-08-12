package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class VideoProbeServiceTests {

	@TempDir
	Path temporaryDirectory;

	private Path storageRoot;
	private Path video;

	@BeforeEach
	void setUp() throws Exception {
		storageRoot = Files.createDirectory(temporaryDirectory.resolve("videos"));
		video = Files.write(storageRoot.resolve("sample.mp4"), new byte[] { 1 });
	}

	@Test
	void returnsMetadataForAValidVideoStream() {
		String json = """
				{
				  "streams": [
				    {"codec_type":"video","codec_name":"h264","width":1920,"height":1080},
				    {"codec_type":"audio","codec_name":"aac"}
				  ],
				  "format": {"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"12.5"}
				}
				""";
		VideoProbeService service = serviceReturning(0, json);

		VideoMetadata metadata = service.probe(video, storageRoot);

		assertEquals("mov,mp4,m4a,3gp,3g2,mj2", metadata.containerFormat());
		assertEquals("h264", metadata.videoCodec());
		assertEquals(java.util.List.of("aac"), metadata.audioCodecs());
		assertEquals(12.5, metadata.durationSeconds());
		assertEquals(1920, metadata.width());
		assertEquals(1080, metadata.height());
	}

	@Test
	void rejectsAFileWithoutVideoStream() {
		String json = """
				{"streams":[{"codec_type":"audio","codec_name":"aac"}],
				 "format":{"format_name":"mp4","duration":"10"}}
				""";

		assertThrows(InvalidVideoFileException.class,
				() -> serviceReturning(0, json).probe(video, storageRoot));
	}

	@Test
	void rejectsMalformedProbeOutput() {
		assertThrows(InvalidVideoFileException.class,
				() -> serviceReturning(0, "not-json").probe(video, storageRoot));
	}

	@Test
	void rejectsInvalidOrIncompleteVideoMetadata() {
		String json = """
				{"streams":[{"codec_type":"video","codec_name":"h264","width":0,"height":1080}],
				 "format":{"format_name":"mp4","duration":"0"}}
				""";

		assertThrows(InvalidVideoFileException.class,
				() -> serviceReturning(0, json).probe(video, storageRoot));
	}

	@Test
	void rejectsFfprobeAbnormalExit() {
		assertThrows(InvalidVideoFileException.class,
				() -> serviceReturning(1, "").probe(video, storageRoot));
	}

	@Test
	void convertsFfprobeTimeoutToSafeValidationFailure() {
		VideoProbeService service = new VideoProbeService(new ObjectMapper()) {
			@Override
			protected ProbeResult executeProbe(Path path, Duration timeout) throws Exception {
				throw new IOException("ffprobe timed out with internal details");
			}
		};

		InvalidVideoFileException exception = assertThrows(
				InvalidVideoFileException.class, () -> service.probe(video, storageRoot));
		assertEquals(InvalidVideoFileException.USER_MESSAGE, exception.getMessage());
	}

	@Test
	void rejectsFilesOutsideStorageBeforeRunningProbe() throws Exception {
		Path outside = Files.write(temporaryDirectory.resolve("outside.mp4"), new byte[] { 1 });

		assertThrows(InvalidVideoFileException.class,
				() -> serviceReturning(0, "{}").probe(outside, storageRoot));
	}

	private VideoProbeService serviceReturning(int exitCode, String output) {
		return new VideoProbeService(new ObjectMapper()) {
			@Override
			protected ProbeResult executeProbe(Path path, Duration timeout) {
				return new ProbeResult(exitCode, output, "internal ffprobe error");
			}
		};
	}
}
