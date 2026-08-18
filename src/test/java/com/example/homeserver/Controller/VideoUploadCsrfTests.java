package com.example.homeserver.Controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import com.example.homeserver.Config.SecurityConfig;
import com.example.homeserver.Entity.Folder;
import com.example.homeserver.Repository.TagRepository;
import com.example.homeserver.Service.FolderService;
import com.example.homeserver.Service.TagService;
import com.example.homeserver.Service.VideoService;
import com.example.homeserver.Service.VideoStreamService;
import com.example.homeserver.Service.VideoUrlImportJobService;
import com.example.homeserver.Service.VideoUrlImportStage;
import com.example.homeserver.Service.InvalidVideoFileException;
import com.example.homeserver.Service.UnsupportedVideoConversionException;

@WebMvcTest(value = VideoController.class, properties = "shared-auth.secret=test-only-shared-auth-secret-32-characters-minimum")
@Import(SecurityConfig.class)
class VideoUploadCsrfTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VideoService videoService;

    @MockBean
    private VideoStreamService videoStreamService;

    @MockBean
    private FolderService folderService;

    @MockBean
    private TagRepository tagRepository;

    @MockBean
    private TagService tagService;

    @MockBean
    private VideoUrlImportJobService videoUrlImportJobService;

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
    void moveWithCsrfMovesVideoToSelectedFolder() throws Exception {
        mockMvc.perform(post("/videos/move")
                        .param("videoId", "7")
                        .param("folderId", "42")
                        .with(user("uploader"))
                        .with(csrf().asHeader()))
                .andExpect(status().isOk());

        verify(videoService).moveVideo(7L, 42L);
    }

    @Test
    void moveWithCsrfCanMoveVideoToRoot() throws Exception {
        mockMvc.perform(post("/videos/move")
                        .param("videoId", "7")
                        .with(user("uploader"))
                        .with(csrf().asHeader()))
                .andExpect(status().isOk());

        verify(videoService).moveVideo(7L, null);
    }

    @Test
    void moveWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post("/videos/move")
                        .param("videoId", "7")
                        .param("folderId", "42")
                        .with(user("uploader")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(videoService);
    }

    @Test
    void editWithCsrfUpdatesTitleAndTags() throws Exception {
        mockMvc.perform(post("/videos/edit")
                        .param("id", "7")
                        .param("title", "変更後の名前")
                        .param("tags", "旅行,iPhone")
                        .with(user("uploader"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(videoService).updateVideo(7L, "変更後の名前", "旅行,iPhone");
    }

    @Test
    void editWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(post("/videos/edit")
                        .param("id", "7")
                        .param("title", "変更後の名前")
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

    @Test
    void unsupportedTranscodeReturnsSafeUserMessage() throws Exception {
        MockMultipartFile video = new MockMultipartFile(
                "file", "unsupported.avi", "video/x-msvideo", new byte[] { 1, 2, 3 });
        doThrow(new UnsupportedVideoConversionException())
                .when(videoService).upload(any(MultipartFile.class), isNull());

        mockMvc.perform(multipart("/videos/upload")
                        .file(video)
                        .with(user("uploader"))
                        .with(csrf().asHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(UnsupportedVideoConversionException.USER_MESSAGE));
    }

    @Test
    void uploadPagePreservesSelectedFolder() throws Exception {
        Folder folder = new Folder();
        folder.setId(42L);
        when(folderService.getFolderById(42L)).thenReturn(folder);

        mockMvc.perform(get("/videos/upload").param("folderId", "42").with(user("uploader")))
                .andExpect(status().isOk())
                .andExpect(view().name("video/upload"))
                .andExpect(model().attribute("currentFolder", folder));
    }

    @Test
    void unexpectedUploadFailureDoesNotExposeInternalMessage() throws Exception {
        MockMultipartFile video = new MockMultipartFile(
                "file", "sample.mp4", "video/mp4", new byte[] { 1 });
        doThrow(new IllegalStateException("jdbc:mysql://secret-host internal failure"))
                .when(videoService).upload(any(MultipartFile.class), isNull());

        mockMvc.perform(multipart("/videos/upload").file(video)
                        .with(user("uploader")).with(csrf().asHeader()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(
                        "アップロードに失敗しました。保存先の空き容量とサーバー設定を確認してください。"));
    }

    @Test
    void urlImportRequiresCsrfAndInvokesService() throws Exception {
        UUID jobId = UUID.randomUUID();
		when(videoUrlImportJobService.startOrReuse("https://example.com/video.mp4", null, "uploader"))
				.thenReturn(new VideoUrlImportJobService.StartResult(jobId, false));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/videos/import-url")
                        .param("url", "https://example.com/video.mp4")
                        .with(user("uploader"))
                        .with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(jobId.toString()));

		verify(videoUrlImportJobService).startOrReuse("https://example.com/video.mp4", null, "uploader");
    }

    @Test
    void urlImportWithoutCsrfIsForbidden() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/videos/import-url")
                        .param("url", "https://example.com/video.mp4")
                        .with(user("uploader")))
                .andExpect(status().isForbidden());
    }

    @Test
    void progressEndpointReturnsOnlyCurrentUsersJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        var progress = new VideoUrlImportJobService.JobProgress(jobId, VideoUrlImportStage.DOWNLOADING,
                "動画をダウンロードしています", 4, 10, 40, 2048, Instant.now(), null, null);
        when(videoUrlImportJobService.find(jobId, "uploader")).thenReturn(Optional.of(progress));

        mockMvc.perform(get("/videos/import-url/progress/{jobId}", jobId).with(user("uploader")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWNLOADING"))
                .andExpect(jsonPath("$.completedSegments").value(4))
                .andExpect(jsonPath("$.percentage").value(40));
    }

    @Test
    void progressEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/videos/import-url/progress/{jobId}", UUID.randomUUID()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void unknownOrOtherUsersJobReturnsNotFound() throws Exception {
        UUID jobId = UUID.randomUUID();
        when(videoUrlImportJobService.find(jobId, "uploader")).thenReturn(Optional.empty());

        mockMvc.perform(get("/videos/import-url/progress/{jobId}", jobId).with(user("uploader")))
                .andExpect(status().isNotFound());
    }

	@Test
	void cancelImportRequiresCsrf() throws Exception {
		UUID jobId = UUID.randomUUID();
		mockMvc.perform(post("/videos/import-url/{jobId}/cancel", jobId).with(user("uploader")))
				.andExpect(status().isForbidden());
		verifyNoInteractions(videoUrlImportJobService);
	}

	@Test
	void cancelImportWithCsrfInvokesOwnerScopedService() throws Exception {
		UUID jobId = UUID.randomUUID();
		var progress = new VideoUrlImportJobService.JobProgress(jobId, VideoUrlImportStage.CANCELLED,
				"キャンセルしました", 0, 0, 0, 0, Instant.now(), Instant.now(), null);
		when(videoUrlImportJobService.cancel(jobId, "uploader")).thenReturn(Optional.of(progress));
		mockMvc.perform(post("/videos/import-url/{jobId}/cancel", jobId)
				.with(user("uploader")).with(csrf()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		verify(videoUrlImportJobService).cancel(jobId, "uploader");
	}
}
