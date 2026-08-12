package com.example.homeserver.Service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class IPhoneSafariCompatibilityServiceTests {

	private final IPhoneSafariCompatibilityService service =
			new IPhoneSafariCompatibilityService();

	@Test
	void compatibleFastStartMp4PassesThrough() {
		assertEquals(VideoCompatibilityDecision.PASSTHROUGH,
				service.evaluate(metadata(true, compatibleVideo(), List.of(compatibleAudio()))));
	}

	@Test
	void compatibleMp4WithoutFastStartRequiresRemux() {
		assertEquals(VideoCompatibilityDecision.REMUX,
				service.evaluate(metadata(false, compatibleVideo(), List.of(compatibleAudio()))));
	}

	@Test
	void compatibleVideoWithoutAudioIsValid() {
		assertEquals(VideoCompatibilityDecision.PASSTHROUGH,
				service.evaluate(metadata(true, compatibleVideo(), List.of())));
	}

	@Test
	void incompatibleAudioOnlyRequiresAudioTranscode() {
		AudioStreamMetadata opus = new AudioStreamMetadata(
				"opus", "Opus", "", 48_000, 2, "stereo", 128_000);

		assertEquals(VideoCompatibilityDecision.TRANSCODE_AUDIO,
				service.evaluate(metadata(true, compatibleVideo(), List.of(opus))));
	}

	@Test
	void lowSampleRateAacFrom001Mp4RequiresAudioTranscodeAnd48kHzIsCompatible() {
		VideoStreamMetadata video = new VideoStreamMetadata(
				"h264", "avc1", "Main", 30, "yuv420p",
				1920, 1080, 30, 8_000_000, 8, false);
		AudioStreamMetadata sourceAudio = new AudioStreamMetadata(
				"aac", "mp4a", "LC", 22_050, 2, "stereo", 128_000);
		AudioStreamMetadata convertedAudio = new AudioStreamMetadata(
				"aac", "mp4a", "LC", 48_000, 2, "stereo", 128_000);

		assertEquals(VideoCompatibilityDecision.TRANSCODE_AUDIO,
				service.evaluate(metadata(true, video, List.of(sourceAudio))));
		assertEquals(VideoCompatibilityDecision.PASSTHROUGH,
				service.evaluate(metadata(true, video, List.of(convertedAudio))));
	}

	@Test
	void tenBitH264RequiresVideoTranscode() {
		VideoStreamMetadata tenBit = new VideoStreamMetadata(
				"h264", "avc1", "High 10", 41, "yuv420p10le",
				1920, 1080, 30, 8_000_000, 10, false);

		assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO,
				service.evaluate(metadata(true, tenBit, List.of(compatibleAudio()))));
		org.junit.jupiter.api.Assertions.assertTrue(
				service.assess(metadata(true, tenBit, List.of(compatibleAudio()))).reasons()
						.contains("pixel format is not compatible 8-bit 4:2:0"));
	}

	@Test
	void incompatibleVideoAndAudioRequireBothTranscodes() {
		VideoStreamMetadata hevc = new VideoStreamMetadata(
				"hevc", "hvc1", "Main 10", 153, "yuv420p10le",
				3840, 2160, 60, 20_000_000, 10, false);
		AudioStreamMetadata opus = new AudioStreamMetadata(
				"opus", "Opus", "", 48_000, 2, "stereo", 128_000);

		assertEquals(VideoCompatibilityDecision.TRANSCODE_BOTH,
				service.evaluate(metadata(true, hevc, List.of(opus))));
	}

	@Test
	void multipleVideoStreamsAreHandledConservatively() {
		VideoMetadata metadata = new VideoMetadata(
				container(true),
				List.of(compatibleVideo(), compatibleVideo()),
				List.of(compatibleAudio()));

		assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO, service.evaluate(metadata));
	}

	@Test
	void attachedPngAlongsideOneNormalVideoIsIgnoredForPassthroughAndRemux() {
		VideoStreamMetadata normal = indexedVideo(compatibleVideo(), 0, false, false, false);
		VideoStreamMetadata cover = auxiliaryPng(2, true, false, false);
		VideoMetadata fastStart = new VideoMetadata(
				container(true), List.of(normal, cover), List.of(compatibleAudio()));
		VideoMetadata nonFastStart = new VideoMetadata(
				container(false), List.of(normal, cover), List.of(compatibleAudio()));

		assertEquals(VideoCompatibilityDecision.PASSTHROUGH, service.evaluate(fastStart));
		assertEquals(VideoCompatibilityDecision.REMUX, service.evaluate(nonFastStart));
	}

	@Test
	void twoNormalVideoStreamsRemainVideoTranscodeEvenWithAuxiliaryImage() {
		VideoMetadata metadata = new VideoMetadata(
				container(true),
				List.of(
						indexedVideo(compatibleVideo(), 0, false, false, false),
						indexedVideo(compatibleVideo(), 1, false, false, false),
						auxiliaryPng(3, true, false, false)),
				List.of(compatibleAudio()));

		assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO, service.evaluate(metadata));
	}

	@Test
	void attachedPictureWithoutNormalVideoRemainsVideoTranscode() {
		VideoMetadata metadata = new VideoMetadata(
				container(true), List.of(auxiliaryPng(2, true, false, false)),
				List.of(compatibleAudio()));

		assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO, service.evaluate(metadata));
	}

	@Test
	void multipleKindsOfAuxiliaryImagesDoNotIncreaseNormalVideoCount() {
		VideoMetadata metadata = new VideoMetadata(
				container(true),
				List.of(
						indexedVideo(compatibleVideo(), 0, false, false, false),
						auxiliaryPng(2, true, false, false),
						auxiliaryPng(3, false, true, false),
						auxiliaryPng(4, false, false, true)),
				List.of(compatibleAudio()));

		assertEquals(VideoCompatibilityDecision.PASSTHROUGH, service.evaluate(metadata));
	}

	@Test
	void movContainerWithCompatibleStreamsRequiresRemux() {
		ContainerMetadata mov = new ContainerMetadata(
				"mov,mp4,m4a,3gp,3g2,mj2", "QuickTime / MOV", "qt", "mov",
				120, 0, 1_000_000, 1_000_000, true);
		VideoMetadata metadata = new VideoMetadata(
				mov, List.of(compatibleVideo()), List.of(compatibleAudio()));

		assertEquals(VideoCompatibilityDecision.REMUX, service.evaluate(metadata));
		org.junit.jupiter.api.Assertions.assertTrue(
				service.assess(metadata).reasons()
						.contains("container file extension is not MP4"));
	}

	@Test
	void iphoneRecordedH264Level50FullRange1440pMovRequiresOnlyRemux() {
		ContainerMetadata mov = new ContainerMetadata(
				"mov,mp4,m4a,3gp,3g2,mj2", "QuickTime / MOV", "qt", "mov",
				120, 0, 100_000_000, 12_000_000, true);
		VideoStreamMetadata iphoneVideo = new VideoStreamMetadata(
				"h264", "avc1", "High", 50, "yuvj420p",
				1920, 1440, 30, 12_000_000, 8, false);
		VideoMetadata metadata = new VideoMetadata(
				mov, List.of(iphoneVideo), List.of(compatibleAudio()));

		assertEquals(VideoCompatibilityDecision.REMUX, service.evaluate(metadata));
	}

	@Test
	void high10ProfileStillRequiresVideoTranscode() {
		VideoStreamMetadata video = new VideoStreamMetadata(
				"h264", "avc1", "High 10", 50, "yuv420p",
				1920, 1080, 30, 8_000_000, 8, false);
		assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO,
				service.evaluate(metadata(true, video, List.of(compatibleAudio()))));
	}

	@Test
	void tenBitPixelFormatStillRequiresVideoTranscode() {
		VideoStreamMetadata video = new VideoStreamMetadata(
				"h264", "avc1", "High", 50, "yuv420p10le",
				1920, 1080, 30, 8_000_000, 10, false);
		assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO,
				service.evaluate(metadata(true, video, List.of(compatibleAudio()))));
	}

	@Test
	void resolutionAbove4kStillRequiresVideoTranscode() {
		VideoStreamMetadata video = new VideoStreamMetadata(
				"h264", "avc1", "High", 52, "yuv420p",
				4096, 2160, 30, 20_000_000, 8, false);
		assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO,
				service.evaluate(metadata(true, video, List.of(compatibleAudio()))));
	}

	@Test
	void frameRateAboveToleranceStillRequiresVideoTranscode() {
		VideoStreamMetadata video = new VideoStreamMetadata(
				"h264", "avc1", "High", 52, "yuv420p",
				1920, 1080, 60.01, 20_000_000, 8, false);
		assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO,
				service.evaluate(metadata(true, video, List.of(compatibleAudio()))));
	}

	@Test
	void levelAbove52StillRequiresVideoTranscode() {
		VideoStreamMetadata video = new VideoStreamMetadata(
				"h264", "avc1", "High", 60, "yuv420p",
				1920, 1080, 30, 8_000_000, 8, false);
		assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO,
				service.evaluate(metadata(true, video, List.of(compatibleAudio()))));
	}

	@Test
	void codecTagOtherThanAvc1StillRequiresVideoTranscode() {
		VideoStreamMetadata video = new VideoStreamMetadata(
				"h264", "avc3", "High", 50, "yuv420p",
				1920, 1440, 30, 8_000_000, 8, false);
		assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO,
				service.evaluate(metadata(true, video, List.of(compatibleAudio()))));
	}

	@Test
	void sevenMp4EquivalentHevcMainSdrPassesThrough() {
		VideoStreamMetadata sevenVideo = hevcVideo(
				"hvc1", "Main", 120, "yuv420p", 1180, 2098, 29.97,
				8, "bt709", "bt709", false, false);

		assertEquals(VideoCompatibilityDecision.PASSTHROUGH,
				service.evaluate(metadata(true, sevenVideo, List.of(compatibleAudio()))));
	}

	@Test
	void sevenMovEquivalentHevcMainSdrRequiresOnlyRemux() {
		VideoStreamMetadata sevenVideo = hevcVideo(
				"hev1", "Main", 120, "yuvj420p", 1180, 2098, 29.97,
				8, "bt709", "bt709", false, false);
		ContainerMetadata mov = new ContainerMetadata(
				"mov,mp4,m4a,3gp,3g2,mj2", "QuickTime / MOV", "qt", "mov",
				120, 0, 20_000_000, 5_000_000, true);

		assertEquals(VideoCompatibilityDecision.REMUX,
				service.evaluate(new VideoMetadata(mov, List.of(sevenVideo), List.of(compatibleAudio()))));
	}

	@Test
	void iphoneScreenRecordingHevcWithSrgbTransferRequiresOnlyRemux() {
		VideoStreamMetadata screenRecording = hevcVideo(
				"hvc1", "Main", 120, "yuvj420p", 884, 1178, 27,
				8, "bt709", "iec61966-2-1", false, false);
		ContainerMetadata mov = new ContainerMetadata(
				"mov,mp4,m4a,3gp,3g2,mj2", "QuickTime / MOV", "qt", "mov",
				120, 0, 20_000_000, 5_000_000, true);

		assertEquals(VideoCompatibilityDecision.REMUX,
				service.evaluate(new VideoMetadata(
						mov, List.of(screenRecording), List.of(compatibleAudio()))));
	}

	@Test
	void pqAndHlgHevcRemainVideoTranscodesEvenWithoutHdrSideData() {
		for (String transfer : List.of("smpte2084", "arib-std-b67")) {
			VideoStreamMetadata video = hevcVideo(
					"hvc1", "Main", 120, "yuv420p", 1920, 1080, 30,
					8, "bt709", transfer, false, false);

			assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO,
					service.evaluate(metadata(true, video, List.of(compatibleAudio()))));
		}
	}

	@Test
	void unspecifiedAndSpecialHevcTransfersRemainVideoTranscodes() {
		for (String transfer : List.of(
				"", "unknown", "bt2020-10", "bt2020-12", "linear", "iec61966-2-4")) {
			VideoStreamMetadata video = hevcVideo(
					"hvc1", "Main", 120, "yuv420p", 1920, 1080, 30,
					8, "bt709", transfer, false, false);

			assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO,
					service.evaluate(metadata(true, video, List.of(compatibleAudio()))));
		}
	}

	@Test
	void tenBitHdrDolbyVisionAndMissingHevcMetadataRemainVideoTranscodes() {
		List<VideoStreamMetadata> incompatible = List.of(
				hevcVideo("hvc1", "Main 10", 120, "yuv420p10le", 3840, 2160, 30,
						10, "bt2020nc", "smpte2084", true, false),
				hevcVideo("hvc1", "Main", 120, "yuv420p", 1920, 1080, 30,
						8, "bt2020nc", "arib-std-b67", true, false),
				hevcVideo("dvh1", "Main", 120, "yuv420p", 1920, 1080, 30,
						8, "bt709", "bt709", false, true),
				hevcVideo("hvc1", "Rext", 120, "yuv420p", 1920, 1080, 30,
						8, "bt709", "bt709", false, false),
				hevcVideo("hvc1", "Main", 0, "yuv420p", 1920, 1080, 30,
						8, "", "", false, false));

		for (VideoStreamMetadata video : incompatible) {
			assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO,
					service.evaluate(metadata(true, video, List.of(compatibleAudio()))));
		}
	}

	private VideoStreamMetadata hevcVideo(String tag, String profile, int level,
			String pixelFormat, int width, int height, double fps, int bitDepth,
			String colorSpace, String colorTransfer, boolean hdr, boolean dolbyVision) {
		return new VideoStreamMetadata(
				"hevc", tag, profile, level, pixelFormat, width, height, fps,
				8_000_000, bitDepth, false, colorSpace, colorTransfer,
				colorSpace.startsWith("bt2020") ? "bt2020" : "bt709", hdr, dolbyVision);
	}

	private VideoMetadata metadata(
			boolean fastStart, VideoStreamMetadata video, List<AudioStreamMetadata> audio) {
		return new VideoMetadata(container(fastStart), List.of(video), audio);
	}

	private ContainerMetadata container(boolean fastStart) {
		return new ContainerMetadata(
				"mov,mp4,m4a,3gp,3g2,mj2", "QuickTime / MOV", "isom", "mp4",
				120, 0, 15_000_000, 1_000_000, fastStart);
	}

	private VideoStreamMetadata compatibleVideo() {
		return new VideoStreamMetadata(
				"h264", "avc1", "High", 41, "yuv420p",
				1920, 1080, 30, 8_000_000, 8, false);
	}

	private VideoStreamMetadata indexedVideo(VideoStreamMetadata source, int index,
			boolean attachedPicture, boolean stillImage, boolean timedThumbnails) {
		return new VideoStreamMetadata(
				source.codec(), source.codecTag(), source.profile(), source.level(),
				source.pixelFormat(), source.width(), source.height(), source.framesPerSecond(),
				source.bitrate(), source.bitsPerRawSample(), attachedPicture,
				source.colorSpace(), source.colorTransfer(), source.colorPrimaries(),
				source.hdrMetadata(), source.dolbyVision(), index, stillImage, timedThumbnails);
	}

	private VideoStreamMetadata auxiliaryPng(int index, boolean attachedPicture,
			boolean stillImage, boolean timedThumbnails) {
		return new VideoStreamMetadata(
				"png", "", "", 0, "rgb24", 1032, 1468, 0,
				0, 8, attachedPicture, "gbr", "", "", false, false,
				index, stillImage, timedThumbnails);
	}

	private AudioStreamMetadata compatibleAudio() {
		return new AudioStreamMetadata(
				"aac", "mp4a", "LC", 48_000, 2, "stereo", 128_000);
	}
}
