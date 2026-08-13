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
				    {"codec_type":"video","codec_name":"h264","codec_tag_string":"avc1",
				     "profile":"High","level":41,"pix_fmt":"yuv420p","width":1920,"height":1080,
				     "avg_frame_rate":"30000/1001","bit_rate":"8000000","bits_per_raw_sample":"8"},
				    {"codec_type":"audio","codec_name":"aac","codec_tag_string":"mp4a","profile":"LC",
				     "sample_rate":"48000","channels":2,"channel_layout":"stereo","bit_rate":"128000"}
				  ],
				  "format": {"format_name":"mov,mp4,m4a,3gp,3g2,mj2","format_long_name":"QuickTime / MOV",
				             "duration":"12.5","start_time":"0","size":"1000000","bit_rate":"8128000",
				             "tags":{"major_brand":"isom"}}
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
		assertEquals("avc1", metadata.videoStreams().getFirst().codecTag());
		assertEquals("High", metadata.videoStreams().getFirst().profile());
		assertEquals(41, metadata.videoStreams().getFirst().level());
		assertEquals("yuv420p", metadata.videoStreams().getFirst().pixelFormat());
		assertEquals(30000.0 / 1001.0, metadata.videoStreams().getFirst().framesPerSecond());
		assertEquals(8_000_000, metadata.videoStreams().getFirst().bitrate());
		assertEquals(48_000, metadata.audioStreams().getFirst().sampleRate());
		assertEquals(2, metadata.audioStreams().getFirst().channels());
		assertEquals(128_000, metadata.audioStreams().getFirst().bitrate());
		assertEquals("isom", metadata.container().majorBrand());
		assertEquals(1_000_000, metadata.container().sizeBytes());
	}

	@Test
	void extractsHevcColorAndHdrMetadataForCompatibilityChecks() {
		String json = """
				{"streams":[{"codec_type":"video","codec_name":"hevc","codec_tag_string":"hvc1",
				 "profile":"Main 10","level":153,"pix_fmt":"yuv420p10le","width":3840,"height":2160,
				 "avg_frame_rate":"30/1","bits_per_raw_sample":"10","color_space":"bt2020nc",
				 "color_transfer":"smpte2084","color_primaries":"bt2020",
				 "side_data_list":[{"side_data_type":"Mastering display metadata"},
				                    {"side_data_type":"DOVI configuration record"}]}],
				 "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"10",
				           "size":"100","tags":{"major_brand":"isom"}}}
				""";

		VideoStreamMetadata stream = serviceReturning(0, json)
				.probe(video, storageRoot).videoStreams().getFirst();

		assertEquals("bt2020nc", stream.colorSpace());
		assertEquals("smpte2084", stream.colorTransfer());
		assertEquals("bt2020", stream.colorPrimaries());
		assertEquals(true, stream.hdrMetadata());
		assertEquals(true, stream.dolbyVision());
	}

	@Test
	void extractsAuxiliaryVideoDispositionsAndOriginalStreamIndex() {
		String json = """
				{"streams":[
				 {"index":0,"codec_type":"video","codec_name":"h264","width":1920,"height":1080,
				  "disposition":{"attached_pic":0,"still_image":0,"timed_thumbnails":0}},
				 {"index":2,"codec_type":"video","codec_name":"png","width":1032,"height":1468,
				  "disposition":{"attached_pic":1,"still_image":0,"timed_thumbnails":0}}],
				 "format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","duration":"10",
				           "size":"100","tags":{"major_brand":"isom"}}}
				""";

		VideoMetadata metadata = serviceReturning(0, json).probe(video, storageRoot);
		VideoStreamMetadata normal = metadata.videoStreams().get(0);
		VideoStreamMetadata cover = metadata.videoStreams().get(1);

		assertEquals(0, normal.streamIndex());
		assertEquals(false, normal.auxiliary());
		assertEquals(2, cover.streamIndex());
		assertEquals(true, cover.attachedPicture());
		assertEquals(true, cover.auxiliary());
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
	void preservesAttachedArtworkWithoutNormalVideoForConservativeCompatibilityDecision() {
		String json = """
				{"streams":[{"index":2,"codec_type":"video","codec_name":"mjpeg","width":600,"height":600,
				              "disposition":{"attached_pic":1}}],
				 "format":{"format_name":"mp4","duration":"10"}}
				""";

		VideoMetadata metadata = serviceReturning(0, json).probe(video, storageRoot);

		assertEquals(true, metadata.videoStreams().getFirst().auxiliary());
		assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO,
				new IPhoneSafariCompatibilityService().evaluate(metadata));
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
