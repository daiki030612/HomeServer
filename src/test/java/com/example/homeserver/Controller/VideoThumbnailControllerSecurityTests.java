package com.example.homeserver.Controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import com.example.homeserver.Config.SecurityConfig;
import com.example.homeserver.Entity.Video;
import com.example.homeserver.Service.CustomThumbnailService;

@WebMvcTest(value = VideoThumbnailController.class,
		properties = "shared-auth.secret=test-only-shared-auth-secret-32-characters-minimum")
@Import(SecurityConfig.class)
class VideoThumbnailControllerSecurityTests {
	@Autowired MockMvc mockMvc;
	@MockBean CustomThumbnailService service;

	@Test
	void authenticatedUploadWithCsrfSucceeds() throws Exception {
		Video video = new Video(); video.setId(7L); video.setCustomThumbnailName("7-a.jpg"); video.setCustomThumbnailVersion(12L);
		when(service.upload(eq(7L), any(MultipartFile.class))).thenReturn(video);
		MockMultipartFile file = new MockMultipartFile("thumbnail", "写真.jpg", "image/jpeg", new byte[] { 1 });
		mockMvc.perform(multipart("/videos/7/thumbnail").file(file).with(user("user")).with(csrf()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.custom").value(true));
		verify(service).upload(eq(7L), any(MultipartFile.class));
	}

	@Test
	void uploadRequiresAuthenticationAndCsrf() throws Exception {
		MockMultipartFile file = new MockMultipartFile("thumbnail", "x.jpg", "image/jpeg", new byte[] { 1 });
		mockMvc.perform(multipart("/videos/7/thumbnail").file(file).with(csrf())).andExpect(status().is3xxRedirection());
		mockMvc.perform(multipart("/videos/7/thumbnail").file(file).with(user("user"))).andExpect(status().isForbidden());
		verifyNoInteractions(service);
	}

	@Test
	void resetIsStateChangingPostAndRequiresCsrf() throws Exception {
		Video video = new Video(); video.setId(7L); video.setCustomThumbnailVersion(13L);
		when(service.reset(7L)).thenReturn(video);
		mockMvc.perform(post("/videos/7/thumbnail/reset").with(user("user")).with(csrf()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.custom").value(false));
		verify(service).reset(7L);
	}
}
