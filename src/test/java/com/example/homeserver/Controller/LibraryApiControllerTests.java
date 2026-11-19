package com.example.homeserver.Controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.homeserver.Config.SecurityConfig;
import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Entity.Video;
import com.example.homeserver.Service.FolderService;
import com.example.homeserver.Service.StorageService;
import com.example.homeserver.Service.TagService;
import com.example.homeserver.Service.VideoService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = LibraryApiController.class,
        properties = "shared-auth.secret=test-only-shared-auth-secret-32-characters-minimum")
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class LibraryApiControllerTests {
    @Autowired MockMvc mvc;
    @MockBean VideoService videos;
    @MockBean FolderService folders;
    @MockBean TagService tags;
    @MockBean StorageService storage;

    @Test void anonymousRequestKeepsExistingSecurityPolicy() throws Exception {
        mvc.perform(get("/api/v1/videos")).andExpect(status().is3xxRedirection());
    }

    @Test void videoListReturnsNativeUrlsAndCurrentFilters() throws Exception {
        Video video = new Video();
        video.setId(7L);
        video.setTitle("sample.mp4");
        video.setCreatedAt(LocalDateTime.of(2026, 10, 1, 12, 30));
        when(videos.findLibraryVideos(null, "sample", "", false, "newest")).thenReturn(List.of(video));
        when(folders.getRootFolders()).thenReturn(List.of());
        when(tags.getAllTags()).thenReturn(List.of());

        mvc.perform(get("/api/v1/videos").param("q", "sample").param("tag", "")
                        .with(user("viewer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videos[0].id").value(7))
                .andExpect(jsonPath("$.videos[0].thumbnailUrl").value("/videos/7/thumbnail?v=0"))
                .andExpect(jsonPath("$.videos[0].streamUrl").value("/videos/play/7"))
                .andExpect(jsonPath("$.filters.q").value("sample"));
    }

    @Test void missingFolderUsesStableErrorEnvelope() throws Exception {
        when(folders.getFolderById(99L)).thenReturn(null);
        mvc.perform(get("/api/v1/videos").param("folderId", "99").with(user("viewer")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FOLDER_NOT_FOUND"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }
}
