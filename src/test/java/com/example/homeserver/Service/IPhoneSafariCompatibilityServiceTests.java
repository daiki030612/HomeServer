package com.example.homeserver.Service;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class IPhoneSafariCompatibilityServiceTests {
    final IPhoneSafariCompatibilityService service = new IPhoneSafariCompatibilityService();

    static VideoMetadata metadata(String codec, String format, String extension, double fps, boolean fastStart) {
        return new VideoMetadata(new ContainerMetadata(format, "", "", extension, 120, 0, 100, 100, fastStart),
                List.of(new VideoStreamMetadata(codec, "", "Main", 0, "yuv420p",
                        1920, 1080, fps, 100, 8, false)),
                List.of(new AudioStreamMetadata("aac", "", "LC", 48000, 2, "stereo", 100)));
    }

    @ParameterizedTest @ValueSource(strings = {"av1", "h264", "hevc"})
    void mp4IsUnchangedAt120fpsWithOrWithoutFastStart(String codec) {
        for (boolean fastStart : List.of(true, false)) {
            assertEquals(VideoCompatibilityDecision.PASSTHROUGH,
                    service.evaluate(metadata(codec, "mov,mp4,m4a,3gp,3g2,mj2", "mp4", 120, fastStart)));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"av1", "h264", "hevc"})
    void mkvWithoutMp4CodecTagsNeedsOnlyRemux(String codec) {
        assertEquals(VideoCompatibilityDecision.REMUX,
                service.evaluate(metadata(codec, "matroska,webm", "mkv", 120, false)));
    }
    @ParameterizedTest @ValueSource(strings = {"mov", "avi"})
    void legacyContainersStillRemux(String extension) {
        assertEquals(VideoCompatibilityDecision.REMUX,
                service.evaluate(metadata("h264", extension.equals("avi") ? "avi" : "mov,mp4", extension, 30, false)));
    }
    @Test void hdrTenBitAndHighResolutionAreStorageCompatible() {
        var m = metadata("hevc", "mov,mp4", "mp4", 240, true);
        var hdr = new VideoStreamMetadata("hevc", "hvc1", "Main 10", 183, "yuv420p10le",
                4096, 2160, 240, 100, 10, false, "bt2020nc", "smpte2084", "bt2020", true, false);
        assertEquals(VideoCompatibilityDecision.PASSTHROUGH,
                service.evaluate(new VideoMetadata(m.container(), List.of(hdr), m.audioStreams())));
    }
    @Test void unsupportedVideoAudioAndContainerHaveSpecificReasons() {
        var m = metadata("vp9", "matroska,webm", "mkv", 30, false);
        assertTrue(service.assess(m).reasons().getFirst().contains("vp9"));
        var opus = new AudioStreamMetadata("opus", "", "", 48000, 2, "stereo", 100);
        m = metadata("av1", "matroska,webm", "mkv", 30, false);
        assertEquals(VideoCompatibilityDecision.TRANSCODE_AUDIO,
                service.evaluate(new VideoMetadata(m.container(), m.videoStreams(), List.of(opus))));
        assertEquals(VideoCompatibilityDecision.TRANSCODE_BOTH,
                service.evaluate(metadata("av1", "mpegts", "mp4", 30, false)));
    }
    @Test void invalidFpsAndAmbiguousVideoStreamsAreRejected() {
        for (double fps : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO,
                    service.evaluate(metadata("av1", "mov,mp4", "mp4", fps, true)));
        }
        var m = metadata("av1", "mov,mp4", "mp4", 120, true);
        assertEquals(VideoCompatibilityDecision.TRANSCODE_VIDEO,
                service.evaluate(new VideoMetadata(m.container(),
                        List.of(m.videoStreams().getFirst(), m.videoStreams().getFirst()), m.audioStreams())));
        var cover = new VideoStreamMetadata("png", "", "", 0, "rgb24", 100, 100, 0, 0, 8, true);
        assertEquals(VideoCompatibilityDecision.PASSTHROUGH,
                service.evaluate(new VideoMetadata(m.container(),
                        List.of(cover, m.videoStreams().getFirst()), m.audioStreams())));
    }
    @Test void lowRateAacAndSilentVideoRemainLossless() {
        var m = metadata("h264", "mov,mp4", "mp4", 30, true);
        var lowRate = new AudioStreamMetadata("aac", "mp4a", "LC", 22050, 2, "stereo", 100);
        assertEquals(VideoCompatibilityDecision.PASSTHROUGH,
                service.evaluate(new VideoMetadata(m.container(), m.videoStreams(), List.of(lowRate))));
        assertEquals(VideoCompatibilityDecision.PASSTHROUGH,
                service.evaluate(new VideoMetadata(m.container(), m.videoStreams(), List.of())));
    }
}
