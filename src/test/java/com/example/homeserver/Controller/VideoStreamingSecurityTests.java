package com.example.homeserver.Controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import com.example.homeserver.Config.SecurityConfig;
import com.example.homeserver.Repository.TagRepository;
import com.example.homeserver.Service.FolderService;
import com.example.homeserver.Service.TagService;
import com.example.homeserver.Service.VideoService;
import com.example.homeserver.Service.VideoStreamService;

@WebMvcTest(VideoController.class)
@Import(SecurityConfig.class)
class VideoStreamingSecurityTests {
	@Autowired
	MockMvc mockMvc;

	@MockBean VideoService videoService;
	@MockBean VideoStreamService videoStreamService;
	@MockBean FolderService folderService;
	@MockBean TagRepository tagRepository;
	@MockBean TagService tagService;

	@Test
	void unauthenticatedVideoRequestRedirectsToLogin() throws Exception {
		mockMvc.perform(get("/videos/play/1").header(HttpHeaders.RANGE, "bytes=0-99"))
				.andExpect(status().is3xxRedirection());

		verifyNoInteractions(videoService, videoStreamService);
	}

	@Test
	void authenticatedRangeRequestUsesValidatedVideoPath() throws Exception {
		Path path = Path.of("validated-video.mp4");
		when(videoService.getVideoPath(1L)).thenReturn(path);
		when(videoStreamService.stream(eq(path), eq("bytes=100-199")))
				.thenReturn(ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
						.header(HttpHeaders.ACCEPT_RANGES, "bytes").build());

		mockMvc.perform(get("/videos/play/1")
					.header(HttpHeaders.RANGE, "bytes=100-199").with(user("viewer")))
				.andExpect(status().isPartialContent())
				.andExpect(header().string(HttpHeaders.ACCEPT_RANGES, "bytes"));

		verify(videoService).getVideoPath(1L);
		verify(videoStreamService).stream(path, "bytes=100-199");
	}

	@Test
	void missingVideoReturns404() throws Exception {
		when(videoService.getVideoPath(999L)).thenReturn(null);

		mockMvc.perform(get("/videos/play/999").with(user("viewer")))
				.andExpect(status().isNotFound());

		verifyNoInteractions(videoStreamService);
	}
}
