package com.example.homeserver.Controller;

import com.example.homeserver.Config.SecurityConfig;
import com.example.homeserver.Service.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = VideoMediaInfoController.class, properties = "shared-auth.secret=test-only-shared-auth-secret-32-characters-minimum")
@Import(SecurityConfig.class)
class VideoMediaInfoControllerTests {
    @Autowired MockMvc mvc;
    @MockBean VideoService videos;
    @MockBean VideoProbeService probe;
    @Test void anonymousRequestsRemainProtected() throws Exception {
        mvc.perform(get("/api/v1/videos/1/media-info")).andExpect(status().is3xxRedirection());
        verifyNoInteractions(probe);
    }
    @Test void bothRoutesExposeCodecWithoutChangingExistingVideoDto() throws Exception {
        Path path = Path.of("/storage/video.mp4");
        when(videos.getVideoPath(1L)).thenReturn(path);
        when(probe.probe(path, path.getParent())).thenReturn(new VideoMetadata(
                new ContainerMetadata("mov,mp4", "", "isom", "mp4", 4700, 0, 4_400_000_000L, 100, false),
                List.of(new VideoStreamMetadata("av1", "av01", "Main", 12, "yuv420p",
                        1920,1080,120,100,8,false)), List.of()));
        for (String route : List.of("/videos/1/media-info", "/api/v1/videos/1/media-info")) {
            mvc.perform(get(route).with(user("viewer")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.videoCodec").value("av1"))
                .andExpect(jsonPath("$.framesPerSecond").value(120))
                .andExpect(jsonPath("$.durationSeconds").value(4700))
                .andExpect(jsonPath("$.contentType").value("video/mp4"));
        }
    }
}
