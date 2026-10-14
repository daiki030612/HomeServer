package com.example.homeserver.Controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import com.example.homeserver.Config.SecurityConfig;
import com.example.homeserver.Repository.TagRepository;
import com.example.homeserver.Service.MediaDownloadService;

@WebMvcTest(value = MediaDownloadController.class,
		properties = "shared-auth.secret=test-only-shared-auth-secret-32-characters-minimum")
@Import(SecurityConfig.class)
class MediaDownloadControllerSecurityTests {
	@Autowired MockMvc mockMvc;
	@MockBean MediaDownloadService downloadService;
	@MockBean TagRepository tagRepository;

	@Test
	void unauthenticatedDownloadsRedirectToLogin() throws Exception {
		mockMvc.perform(get("/videos/1/download")).andExpect(status().is3xxRedirection());
		mockMvc.perform(get("/folders/1/download")).andExpect(status().is3xxRedirection());
		verifyNoInteractions(downloadService);
	}

	@Test
	void authenticatedVideoDownloadReturnsAttachment() throws Exception {
		when(downloadService.video(1L)).thenReturn(ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''movie.mp4")
				.contentType(MediaType.valueOf("video/mp4")).contentLength(123).build());

		mockMvc.perform(get("/videos/1/download").with(user("viewer")))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename*=UTF-8''movie.mp4"))
				.andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, 123));
	}

	@Test
	void authenticatedFolderDownloadAndMissingResourcesKeepServiceStatuses() throws Exception {
		when(downloadService.folder(1L)).thenReturn(ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''folder.zip")
				.contentType(MediaType.valueOf("application/zip")).build());
		when(downloadService.video(99L)).thenReturn(ResponseEntity.notFound().build());
		when(downloadService.folder(99L)).thenReturn(ResponseEntity.notFound().build());

		mockMvc.perform(get("/folders/1/download").with(user("viewer")))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/zip"));
		mockMvc.perform(get("/videos/99/download").with(user("viewer")))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/folders/99/download").with(user("viewer")))
				.andExpect(status().isNotFound());
	}
}
