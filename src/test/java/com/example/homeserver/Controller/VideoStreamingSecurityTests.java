package com.example.homeserver.Controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;

import jakarta.servlet.http.Cookie;

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
import com.example.homeserver.Entity.Video;
import com.example.homeserver.Auth.SharedAuthTokenService;
import com.example.homeserver.Repository.TagRepository;
import com.example.homeserver.Service.FolderService;
import com.example.homeserver.Service.TagService;
import com.example.homeserver.Service.VideoService;
import com.example.homeserver.Service.VideoStreamService;

@WebMvcTest(value = VideoController.class, properties = "shared-auth.secret=test-only-shared-auth-secret-32-characters-minimum")
@Import(SecurityConfig.class)
class VideoStreamingSecurityTests {
	@Autowired
	MockMvc mockMvc;
	@Autowired
	SharedAuthTokenService sharedAuthTokenService;

	@MockBean VideoService videoService;
	@MockBean VideoStreamService videoStreamService;
	@MockBean FolderService folderService;
	@MockBean TagRepository tagRepository;
	@MockBean TagService tagService;
	@MockBean com.example.homeserver.Service.VideoUrlImportJobService videoUrlImportJobService;

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

	@Test
	void modifyingRequestRequiresCsrf() throws Exception {
		mockMvc.perform(post("/videos/delete/1").with(user("viewer")))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/videos/delete/1").with(user("viewer")).with(csrf()))
				.andExpect(status().is3xxRedirection());

		verify(videoService).deleteVideo(1L);
	}

	@Test
	void logoutPostWithCsrfReturnsToLogin() throws Exception {
		mockMvc.perform(post("/logout").with(user("viewer")).with(csrf()))
				.andExpect(status().is3xxRedirection())
				.andExpect(header().string(HttpHeaders.LOCATION, "/login?logout"))
				.andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.allOf(
						org.hamcrest.Matchers.containsString("MEDIA_AUTH="),
						org.hamcrest.Matchers.containsString("Max-Age=0"),
						org.hamcrest.Matchers.containsString("Path=/"))));
	}

	@Test
	void favoriteUpdateRequiresAuthenticationAndCsrf() throws Exception {
		mockMvc.perform(post("/videos/1/favorite")
				.contentType("application/json").content("{\"favorite\":true}"))
				.andExpect(status().isForbidden());

		mockMvc.perform(post("/videos/1/favorite").with(user("viewer"))
				.contentType("application/json").content("{\"favorite\":true}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void favoriteUpdateWithCsrfReturnsDesiredState() throws Exception {
		Video video = new Video();
		video.setId(1L);
		video.setFavorite(true);
		when(videoService.setFavorite(1L, true)).thenReturn(video);

		mockMvc.perform(post("/videos/1/favorite").with(user("viewer")).with(csrf().asHeader())
				.contentType("application/json").content("{\"favorite\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(1))
				.andExpect(jsonPath("$.favorite").value(true));
	}

	@Test
	void favoriteUpdateReturns404ForMissingVideo() throws Exception {
		when(videoService.setFavorite(999L, true)).thenReturn(null);

		mockMvc.perform(post("/videos/999/favorite").with(user("viewer")).with(csrf())
				.contentType("application/json").content("{\"favorite\":true}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void signedMediaAuthCookieRestoresAuthenticationBeforeAuthorization() throws Exception {
		Path path = Path.of("validated-video.mp4");
		when(videoService.getVideoPath(1L)).thenReturn(path);
		when(videoStreamService.stream(eq(path), eq("bytes=100-199")))
				.thenReturn(ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
						.header(HttpHeaders.ACCEPT_RANGES, "bytes").build());
		String token = sharedAuthTokenService.create("viewer", List.of("ROLE_USER"));

		mockMvc.perform(get("/videos/play/1")
					.header(HttpHeaders.RANGE, "bytes=100-199")
					.cookie(new Cookie("MEDIA_AUTH", token)))
				.andExpect(status().isPartialContent());
	}
}
