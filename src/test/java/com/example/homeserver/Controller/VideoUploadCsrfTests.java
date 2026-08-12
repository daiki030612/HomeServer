package com.example.homeserver.Controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import com.example.homeserver.Config.SecurityConfig;
import com.example.homeserver.Repository.TagRepository;
import com.example.homeserver.Service.FolderService;
import com.example.homeserver.Service.TagService;
import com.example.homeserver.Service.VideoService;
import com.example.homeserver.Service.InvalidVideoFileException;

@WebMvcTest(VideoController.class)
@Import(SecurityConfig.class)
class VideoUploadCsrfTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VideoService videoService;

    @MockBean
    private FolderService folderService;

    @MockBean
    private TagRepository tagRepository;

    @MockBean
    private TagService tagService;

    @Test
    void uploadWithCsrfHeaderSucceeds() throws Exception {
        MockMultipartFile video = new MockMultipartFile(
                "file",
                "sample.mp4",
                "video/mp4",
                new byte[] { 1, 2, 3 });

        mockMvc.perform(multipart("/videos/upload")
                        .file(video)
                        .with(user("uploader"))
                        .with(csrf().asHeader()))
                .andExpect(status().isOk());

        verify(videoService).upload(any(MultipartFile.class), isNull());
    }

    @Test
    void uploadWithoutCsrfTokenIsForbidden() throws Exception {
        MockMultipartFile video = new MockMultipartFile(
                "file",
                "sample.mp4",
                "video/mp4",
                new byte[] { 1, 2, 3 });

        mockMvc.perform(multipart("/videos/upload")
                        .file(video)
                        .with(user("uploader")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(videoService);
    }

    @Test
    void invalidVideoReturnsOnlySafeUserMessage() throws Exception {
        MockMultipartFile video = new MockMultipartFile(
                "file", "disguised.mp4", "video/mp4", new byte[] { 1, 2, 3 });
        doThrow(new InvalidVideoFileException(
                new IllegalStateException("sensitive ffprobe details")))
                .when(videoService).upload(any(MultipartFile.class), isNull());

        mockMvc.perform(multipart("/videos/upload")
                        .file(video)
                        .with(user("uploader"))
                        .with(csrf().asHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(InvalidVideoFileException.USER_MESSAGE));
    }
}
