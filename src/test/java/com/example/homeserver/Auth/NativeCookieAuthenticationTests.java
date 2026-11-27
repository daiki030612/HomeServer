package com.example.homeserver.Auth;

import com.example.homeserver.Config.SecurityConfig;
import com.example.homeserver.Controller.ApiExceptionHandler;
import com.example.homeserver.Controller.LibraryApiController;
import com.example.homeserver.Controller.NativeMutationApiController;
import com.example.homeserver.Entity.Video;
import com.example.homeserver.Service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercise the actual native controllers with the unchanged signed cookie format. */
@WebMvcTest(controllers = {LibraryApiController.class, NativeMutationApiController.class}, properties = {
        "shared-auth.secret=test-only-shared-auth-secret-32-characters-minimum",
        "navigation.video.url=https://video.media.example.com"})
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class NativeCookieAuthenticationTests {
    @Autowired MockMvc mvc;
    @Autowired SharedAuthTokenService tokens;
    @Autowired ObjectMapper json;
    @MockBean VideoService videos;
    @MockBean FolderService folders;
    @MockBean TagService tags;
    @MockBean StorageService storage;
    @MockBean CustomThumbnailService thumbnails;
    @MockBean VideoUrlImportJobService imports;

    private static RequestPostProcessor publicRoute() {
        return request -> {
            request.setScheme("https");
            request.setSecure(true);
            request.setServerName("video.media.example.com");
            request.setServerPort(443);
            request.setLocalAddr("127.0.0.1");
            return request;
        };
    }
    private Cookie sharedCookie() {
        return new Cookie("MEDIA_AUTH", tokens.create("native-user", List.of("ROLE_USER")));
    }
    @Test void nativeReadApiAcceptsExistingSharedCookie() throws Exception {
        when(videos.findLibraryVideos(null, null, null, false, "newest")).thenReturn(List.of());
        when(folders.getRootFolders()).thenReturn(List.of());
        when(tags.getAllTags()).thenReturn(List.of());
        mvc.perform(get("/api/v1/videos").with(publicRoute()).cookie(sharedCookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.videos").isArray());
        mvc.perform(get("/api/v1/videos").with(publicRoute()))
                .andExpect(redirectedUrl("/login"));
    }
    @Test void nativeMutationUsesRealCsrfSessionAndExistingCookie() throws Exception {
        Cookie cookie = sharedCookie();
        var csrfResult = mvc.perform(get("/api/v1/csrf").with(publicRoute()).cookie(cookie))
                .andExpect(status().isOk()).andReturn();
        var csrf = json.readTree(csrfResult.getResponse().getContentAsString());
        var session = (MockHttpSession) csrfResult.getRequest().getSession(false);
        Video video = new Video();
        video.setId(7L); video.setTitle("sample.mp4"); video.setFavorite(true);
        when(videos.setFavorite(7L, true)).thenReturn(video);
        mvc.perform(put("/api/v1/videos/7/favorite").with(publicRoute()).cookie(cookie).session(session)
                        .header(csrf.get("headerName").asText(), csrf.get("token").asText())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.favorite").value(true));
        mvc.perform(put("/api/v1/videos/7/favorite").with(publicRoute()).cookie(cookie).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"favorite\":true}"))
                .andExpect(status().isForbidden());
    }
}
