package com.example.homeserver.Auth;

import com.example.homeserver.Config.SecurityConfig;
import com.example.homeserver.Controller.LoginController;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = LoginController.class, properties = {
    "shared-auth.secret=test-only-shared-auth-secret-32-characters-minimum",
    "shared-auth.cookie-secure=true", "navigation.video.url=https://video.media.example.com",
    "video-auth.direct-origin=http://192.0.2.10:8081",
    "shared-auth.cookie-domain=media.example.com",
    "shared-auth.image-url=https://images.media.example.com/gallery" })
@Import({SecurityConfig.class, RouteAuthenticationTests.Accounts.class, RouteAuthenticationTests.Endpoints.class})
class RouteAuthenticationTests {
    @Autowired MockMvc mvc;

    @TestConfiguration static class Accounts {
        @Bean UserDetailsService users(org.springframework.security.crypto.password.PasswordEncoder encoder) {
            return new InMemoryUserDetailsManager(User.withUsername("existing-user")
                    .password(encoder.encode("test-password")).roles("USER").build());
        }
    }
    @org.springframework.boot.test.context.TestComponent
    @RestController static class Endpoints {
        @GetMapping("/videos") String videos() { return "library"; }
        @GetMapping("/videos/upload") String uploadPage() { return "upload"; }
        @PostMapping("/videos/upload") long upload(@RequestParam MultipartFile file) { return file.getSize(); }
    }
    private static RequestPostProcessor route(boolean direct) {
        return request -> {
            request.setScheme(direct ? "http" : "https");
            request.setSecure(!direct);
            request.setServerName(direct ? "192.0.2.10" : "video.media.example.com");
            request.setServerPort(direct ? 8081 : 443);
            request.setLocalAddr(direct ? "192.0.2.10" : "127.0.0.1");
            return request;
        };
    }
    private MvcResult login(boolean direct, MockHttpSession session, String password) throws Exception {
        return mvc.perform(post("/login").with(route(direct)).session(session).with(csrf())
                .param("username", "existing-user").param("password", password))
                .andExpect(status().is3xxRedirection()).andReturn();
    }
    @Test void directLoginRestoresPageAndSessionAndDoesNotIssueSharedCookie() throws Exception {
        var challenge = mvc.perform(get("/videos/upload?folderId=42").with(route(true)))
                .andExpect(redirectedUrl("/login")).andReturn();
        var session = (MockHttpSession) challenge.getRequest().getSession(false);
        String oldId = session.getId();
        var result = login(true, session, "test-password");
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/videos/upload?folderId=42");
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(session.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/videos").with(route(true)).session(session)).andExpect(status().isOk());
        // A direct session must not authenticate a public-domain request.
        mvc.perform(get("/videos").with(route(false)).session(session)).andExpect(redirectedUrl("/login"));
        mvc.perform(multipart("/videos/upload").file(new MockMultipartFile("file", "test.mp4", "video/mp4", new byte[]{1,2,3}))
                .with(route(true)).session(session).with(csrf().asHeader()))
                .andExpect(status().isOk()).andExpect(content().string("3"));
        mvc.perform(post("/logout").with(route(true)).session(session).with(csrf()))
                .andExpect(redirectedUrl("/login?logout"));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/videos").with(route(true))).andExpect(redirectedUrl("/login"));
    }
    @Test void publicLoginPreservesCookieAuthenticationAndLogout() throws Exception {
        var challenge = mvc.perform(get("/videos/upload?folderId=9").with(route(false))).andReturn();
        var result = login(false, (MockHttpSession) challenge.getRequest().getSession(false), "test-password");
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/videos/upload?folderId=9");
        Cookie cookie = result.getResponse().getCookie("MEDIA_AUTH");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getDomain()).isEqualTo("media.example.com");
        mvc.perform(get("/videos").with(route(false)).cookie(cookie)).andExpect(status().isOk());
        mvc.perform(multipart("/videos/upload").file(new MockMultipartFile("file", new byte[]{1}))
                .with(route(false)).cookie(cookie).with(csrf().asHeader())).andExpect(status().isOk());
        mvc.perform(post("/logout").with(route(false)).cookie(cookie).with(csrf()))
                .andExpect(redirectedUrl("/login?logout"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        // Shared cookies from iOS or other applications cannot establish direct authentication.
        mvc.perform(get("/videos").with(route(true)).cookie(cookie)).andExpect(redirectedUrl("/login"));
    }
    @Test void failedLoginAndMissingCsrfDoNotAuthenticate() throws Exception {
        for (boolean direct : List.of(true, false)) {
            var session = new MockHttpSession();
            login(direct, session, "wrong");
            mvc.perform(get("/videos").with(route(direct)).session(session)).andExpect(redirectedUrl("/login"));
            mvc.perform(post("/login").with(route(direct)).param("username", "existing-user")
                    .param("password", "test-password")).andExpect(status().isForbidden());
            mvc.perform(post("/logout").with(route(direct))).andExpect(status().isForbidden());
            mvc.perform(multipart("/videos/upload").file(new MockMultipartFile("file", new byte[]{1}))
                    .with(route(direct))).andExpect(status().isForbidden());
        }
    }
    @Test void rejectsForgedHostAndForwardedHeaders() throws Exception {
        mvc.perform(get("/videos").with(route(true)).with(r -> { r.setLocalAddr("127.0.0.1"); return r; }))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/videos").with(route(true)).with(r -> { r.setServerName("attacker.example"); return r; }))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/videos").with(route(false)).with(r -> { r.setServerName("attacker.example"); return r; }))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/videos").with(route(true)).with(r -> { r.setContextPath("//attacker.example"); return r; }))
                .andExpect(status().isBadRequest());
        for (String name : List.of("Forwarded", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-For")) {
            mvc.perform(get("/videos").with(route(true)).header(name, "attacker.example"))
                    .andExpect(status().isBadRequest());
        }
    }
    @Test void configuredImageContinueWorksOnlyOnPublicRoute() throws Exception {
        for (boolean direct : List.of(true, false)) {
            mvc.perform(post("/login").with(route(direct)).with(csrf()).param("username", "existing-user")
                    .param("password", "test-password").param("continue", "https://images.media.example.com/gallery"))
                    .andExpect(redirectedUrl(direct ? "/videos" : "https://images.media.example.com/gallery"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"https://attacker.example/", "//attacker.example/", "/\\attacker.example/",
        "/%2fattacker.example/", "/%5cattacker.example/", "https://images.media.example.com:8081/gallery"})
    void rejectsUnsafeContinue(String destination) throws Exception {
        for (boolean direct : List.of(true, false)) {
            mvc.perform(post("/login").with(route(direct)).with(csrf()).param("username", "existing-user")
                    .param("password", "test-password").param("continue", destination))
                    .andExpect(redirectedUrl("/videos"));
        }
    }
}
