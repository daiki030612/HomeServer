package com.example.homeserver.Controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.homeserver.Config.SecurityConfig;
import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Entity.Video;
import com.example.homeserver.Service.CustomThumbnailService;
import com.example.homeserver.Service.FolderService;
import com.example.homeserver.Service.TagService;
import com.example.homeserver.Service.VideoService;
import com.example.homeserver.Service.VideoUrlImportJobService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = NativeMutationApiController.class,
        properties = "shared-auth.secret=test-only-shared-auth-secret-32-characters-minimum")
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class NativeMutationApiControllerTests {
    @Autowired MockMvc mvc;
    @MockBean VideoService videos;
    @MockBean FolderService folders;
    @MockBean TagService tags;
    @MockBean CustomThumbnailService thumbnails;
    @MockBean VideoUrlImportJobService imports;

    @Test void uploadErrorsHaveDistinctNativeCodes() throws Exception {
        var file = new org.springframework.mock.web.MockMultipartFile("file", "sample.mkv",
                "video/x-matroska", new byte[]{1});
        for (String code : java.util.List.of("INSUFFICIENT_STORAGE", "REMUX_FAILED", "THUMBNAIL_FAILED")) {
            int status = code.equals("INSUFFICIENT_STORAGE") ? 507 : 422;
            org.mockito.Mockito.doThrow(new com.example.homeserver.Service.VideoUploadException(code, status, "failure"))
                    .when(videos).upload(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/videos/uploads")
                    .file(file).with(user("viewer")).with(csrf()))
                .andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
        }
    }

    @Test void cookieMutationStillRequiresCsrf() throws Exception {
        mvc.perform(put("/api/v1/videos/7/favorite").with(user("viewer"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test void favoriteDelegatesToExistingServiceAndReturnsVideoDto() throws Exception {
        Video video = new Video(); video.setId(7L); video.setTitle("sample.mp4"); video.setFavorite(true);
        when(videos.setFavorite(7L, true)).thenReturn(video);
        mvc.perform(put("/api/v1/videos/7/favorite").with(user("viewer")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.favorite").value(true));
    }

    @Test void nonEmptyFolderUsesConflictEnvelope() throws Exception {
        Folder folder = new Folder(); folder.setId(3L); folder.setName("Keep");
        when(folders.getFolderById(3L)).thenReturn(folder);
        when(folders.deleteFolder(3L)).thenReturn(false);
        mvc.perform(delete("/api/v1/folders/3").with(user("viewer")).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FOLDER_NOT_EMPTY"));
    }

    @Test void movingToCurrentFolderUsesConflictEnvelope() throws Exception {
        Folder folder = new Folder(); folder.setId(3L); folder.setName("Current");
        Video video = new Video(); video.setId(7L); video.setTitle("sample.mp4"); video.setFolder(folder);
        when(videos.getVideoById(7L)).thenReturn(video);
        when(folders.getFolderById(3L)).thenReturn(folder);
        mvc.perform(put("/api/v1/videos/7/folder").with(user("viewer")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"folderId\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ALREADY_IN_FOLDER"));
    }
}
