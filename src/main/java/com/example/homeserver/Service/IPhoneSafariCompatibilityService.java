package com.example.homeserver.Service;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

@Service
public class IPhoneSafariCompatibilityService {

	private static final Set<String> H264_PROFILES = Set.of(
			"baseline", "constrained baseline", "main", "high");
	private static final Set<String> H264_8_BIT_420_PIXEL_FORMATS = Set.of(
			"yuv420p", "yuvj420p");
	private static final int MAX_H264_LEVEL = 52;
	private static final int MAX_HEVC_LEVEL = 153;
	private static final Set<String> HEVC_SDR_COLOR_TRANSFERS = Set.of(
			"bt709", "iec61966-2-1", "smpte170m");
	private static final int MAX_LONG_SIDE = 3840;
	private static final int MAX_SHORT_SIDE = 2160;
	private static final long MAX_PIXEL_COUNT = 3840L * 2160L;
	private static final double MAX_FRAMES_PER_SECOND = 60.001;

	public VideoCompatibilityDecision evaluate(VideoMetadata metadata) {
		return assess(metadata).decision();
	}

	public VideoCompatibilityAssessment assess(VideoMetadata metadata) {
		List<String> videoReasons = videoIncompatibilityReasons(metadata);
		List<String> audioReasons = audioIncompatibilityReasons(metadata);
		List<String> containerReasons = containerIncompatibilityReasons(metadata.container());
		boolean videoCompatible = videoReasons.isEmpty();
		boolean audioCompatible = audioReasons.isEmpty();
		VideoCompatibilityDecision decision;

		if (videoCompatible && audioCompatible) {
			decision = containerReasons.isEmpty()
					? VideoCompatibilityDecision.PASSTHROUGH
					: VideoCompatibilityDecision.REMUX;
		} else if (videoCompatible) {
			decision = VideoCompatibilityDecision.TRANSCODE_AUDIO;
		} else if (audioCompatible) {
			decision = VideoCompatibilityDecision.TRANSCODE_VIDEO;
		} else {
			decision = VideoCompatibilityDecision.TRANSCODE_BOTH;
		}

		List<String> reasons = new ArrayList<>();
		if (decision == VideoCompatibilityDecision.PASSTHROUGH) {
			reasons.add("container, video, audio, and Fast Start are compatible");
		} else if (decision == VideoCompatibilityDecision.REMUX) {
			reasons.addAll(containerReasons);
		} else {
			reasons.addAll(videoReasons);
			reasons.addAll(audioReasons);
		}
		return new VideoCompatibilityAssessment(decision, List.copyOf(reasons));
	}

	private List<String> videoIncompatibilityReasons(VideoMetadata metadata) {
		List<String> reasons = new ArrayList<>();
		List<VideoStreamMetadata> normalVideoStreams = metadata.normalVideoStreams();
		if (normalVideoStreams.size() != 1) {
			reasons.add("normal video stream count must be exactly one");
			return reasons;
		}

		VideoStreamMetadata video = normalVideoStreams.getFirst();
		String codec = normalize(video.codec());
		if ("h264".equals(codec)) {
			addH264Reasons(video, reasons);
		} else if ("hevc".equals(codec) || "h265".equals(codec)) {
			addHevcReasons(video, reasons);
		} else {
			reasons.add("video codec is not compatible H.264 or HEVC");
		}
		if (!H264_8_BIT_420_PIXEL_FORMATS.contains(normalize(video.pixelFormat()))) {
			reasons.add("pixel format is not compatible 8-bit 4:2:0");
		}
		if (video.bitsPerRawSample() > 8) reasons.add("video bit depth is above 8-bit");
		int longSide = Math.max(video.width(), video.height());
		int shortSide = Math.min(video.width(), video.height());
		long pixelCount = (long) video.width() * video.height();
		if (video.width() <= 0 || video.height() <= 0
				|| longSide > MAX_LONG_SIDE || shortSide > MAX_SHORT_SIDE
				|| pixelCount > MAX_PIXEL_COUNT) {
			reasons.add("resolution exceeds the supported 4K bounds");
		}
		if (video.width() % 2 != 0 || video.height() % 2 != 0) reasons.add("video dimensions must be even");
		if (!Double.isFinite(video.framesPerSecond())
				|| video.framesPerSecond() <= 0
				|| video.framesPerSecond() > MAX_FRAMES_PER_SECOND) {
			reasons.add("frame rate is invalid or above 60 fps");
		}
		return reasons;
	}

	private void addH264Reasons(VideoStreamMetadata video, List<String> reasons) {
		if (!"avc1".equals(normalize(video.codecTag()))) reasons.add("video codec tag is not avc1");
		if (!H264_PROFILES.contains(normalize(video.profile()))) reasons.add("H.264 profile is not compatible");
		if (video.level() <= 0 || video.level() > MAX_H264_LEVEL) reasons.add("H.264 level is missing or above 5.2");
	}

	private void addHevcReasons(VideoStreamMetadata video, List<String> reasons) {
		String tag = normalize(video.codecTag());
		if (!("hvc1".equals(tag) || "hev1".equals(tag))) {
			reasons.add("HEVC codec tag is not hvc1 or hev1");
		}
		if (!"main".equals(normalize(video.profile()))) reasons.add("HEVC profile is not 8-bit Main");
		if (video.level() <= 0 || video.level() > MAX_HEVC_LEVEL) {
			reasons.add("HEVC level is missing or above 5.1");
		}
		String transfer = normalize(video.colorTransfer());
		if (!HEVC_SDR_COLOR_TRANSFERS.contains(transfer)) {
			reasons.add("HEVC SDR color transfer metadata is missing or unsupported");
		}
		if (video.hdrMetadata()) reasons.add("HEVC HDR metadata is present");
		if (video.dolbyVision()) reasons.add("Dolby Vision is not accepted as SDR HEVC");
		String primaries = normalize(video.colorPrimaries());
		if ("bt2020".equals(primaries)) reasons.add("BT.2020 HEVC is treated conservatively as HDR");
	}

	private List<String> audioIncompatibilityReasons(VideoMetadata metadata) {
		List<String> reasons = new ArrayList<>();
		if (metadata.audioStreams().isEmpty()) {
			return reasons;
		}
		if (metadata.audioStreams().size() != 1) {
			reasons.add("audio stream count must be zero or one");
			return reasons;
		}

		AudioStreamMetadata audio = metadata.audioStreams().getFirst();
		String profile = normalize(audio.profile());
		if (!"aac".equals(normalize(audio.codec()))) reasons.add("audio codec is not AAC");
		if (!"mp4a".equals(normalize(audio.codecTag()))) reasons.add("audio codec tag is not mp4a");
		if (!("lc".equals(profile) || "aac lc".equals(profile))) reasons.add("AAC profile is not AAC-LC");
		if (audio.sampleRate() != 44_100 && audio.sampleRate() != 48_000) reasons.add("audio sample rate is not 44.1 or 48 kHz");
		if (audio.channels() < 1 || audio.channels() > 2) reasons.add("audio channel count is not mono or stereo");
		return reasons;
	}

	private List<String> containerIncompatibilityReasons(ContainerMetadata container) {
		List<String> reasons = new ArrayList<>();
		if (!"mp4".equals(normalize(container.fileExtension()))) reasons.add("container file extension is not MP4");
		if (!normalize(container.formatName()).contains("mp4")) reasons.add("container is not recognized as MP4");
		if ("qt".equals(normalize(container.majorBrand()))) reasons.add("container major brand is QuickTime");
		if (!container.fastStart()) reasons.add("MP4 Fast Start is not enabled");
		return reasons;
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}
}
