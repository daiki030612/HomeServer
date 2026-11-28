package com.example.homeserver.Controller;

import com.example.homeserver.Service.VideoService;
import com.example.homeserver.Service.VideoProbeService;
import java.nio.file.Path;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Additive endpoint: no entity/schema change, usable by both web and native clients. */
@RestController
public class VideoMediaInfoController {
    private final VideoService videos;
    private final VideoProbeService probe;
    public VideoMediaInfoController(VideoService videos, VideoProbeService probe) {
        this.videos = videos; this.probe = probe;
    }
    @GetMapping({"/videos/{id}/media-info", "/api/v1/videos/{id}/media-info"})
    public MediaInfo info(@PathVariable Long id) {
        Path path = videos.getVideoPath(id);
        if (path == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var m = probe.probe(path, path.getParent());
        var streams = m.normalVideoStreams();
        if (streams.size() != 1) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY);
        var v = streams.getFirst();
        return new MediaInfo("video/mp4", v.codec(), v.profile(), v.level(), v.pixelFormat(),
                v.width(), v.height(), v.framesPerSecond(), m.durationSeconds(),
                "再生可否は端末・OS・デコーダーに依存します。非対応の場合も保存動画を自動再圧縮しません。");
    }
    public record MediaInfo(String contentType, String videoCodec, String profile, int level,
            String pixelFormat, int width, int height, double framesPerSecond,
            double durationSeconds, String compatibilityNote) {}
}
