package com.example.homeserver.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Storage/muxing policy, not a promise that every iPhone can decode the file.
 * The historic bean name is retained for existing integrations.
 */
@Service
public class IPhoneSafariCompatibilityService {
    public VideoCompatibilityDecision evaluate(VideoMetadata metadata) {
        return assess(metadata).decision();
    }

    public VideoCompatibilityAssessment assess(VideoMetadata metadata) {
        List<String> videoReasons = new ArrayList<>();
        List<String> audioReasons = new ArrayList<>();
        var videos = metadata.normalVideoStreams();
        if (videos.size() != 1) {
            videoReasons.add("通常の映像ストリームは1本だけ指定してください。");
        } else {
            var v = videos.getFirst();
            if (!Set.of("h264", "hevc", "av1").contains(normalize(v.codec()))) {
                videoReasons.add("非対応の映像コーデック: " + v.codec() + "（H.264 / HEVC / AV1対応）");
            }
            if (v.width() <= 0 || v.height() <= 0 || !Double.isFinite(v.framesPerSecond())
                    || v.framesPerSecond() <= 0) {
                videoReasons.add("解像度またはフレームレートの情報が不正です。");
            }
        }
        // AAC remains lossless even at 22.05 kHz or with more than two channels.
        // Unsupported audio is rejected; it must never trigger automatic re-encoding.
        if (metadata.audioStreams().size() > 1) {
            audioReasons.add("音声ストリームは0本または1本にしてください。");
        }
        for (var a : metadata.audioStreams()) {
            if (!Set.of("aac", "mp3", "ac3", "eac3", "alac").contains(normalize(a.codec()))) {
                audioReasons.add("非対応の音声コーデック: " + a.codec()
                        + "（AAC / MP3 / AC-3 / E-AC-3 / ALAC対応。自動再圧縮は行いません）");
            }
        }
        var reasons = new ArrayList<String>();
        reasons.addAll(videoReasons);
        reasons.addAll(audioReasons);
        if (!reasons.isEmpty()) {
            var decision = videoReasons.isEmpty() ? VideoCompatibilityDecision.TRANSCODE_AUDIO
                    : audioReasons.isEmpty() ? VideoCompatibilityDecision.TRANSCODE_VIDEO
                    : VideoCompatibilityDecision.TRANSCODE_BOTH;
            return new VideoCompatibilityAssessment(decision, List.copyOf(reasons));
        }
        var c = metadata.container();
        String format = normalize(c.formatName());
        boolean mp4 = "mp4".equals(normalize(c.fileExtension())) && format.contains("mp4")
                && !"qt".equals(normalize(c.majorBrand()));
        boolean supportedContainer = format.contains("mp4") || format.contains("matroska")
                || format.equals("avi");
        if (!supportedContainer) {
            return new VideoCompatibilityAssessment(VideoCompatibilityDecision.TRANSCODE_BOTH,
                    List.of("非対応のコンテナ: " + c.formatName()));
        }
        // Already valid MP4 is preserved byte-for-byte (Fast Start is optional for Range playback).
        return new VideoCompatibilityAssessment(mp4 ? VideoCompatibilityDecision.PASSTHROUGH
                : VideoCompatibilityDecision.REMUX, List.of(mp4 ? "MP4原本を保存" : "MP4へストリームコピー"));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
