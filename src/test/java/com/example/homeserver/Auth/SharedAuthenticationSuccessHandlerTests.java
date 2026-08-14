package com.example.homeserver.Auth;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class SharedAuthenticationSuccessHandlerTests {
    @Test void issuesHostWideCookieAndAllowsImagePortRedirect() throws Exception {
        SharedAuthProperties properties = properties();
        SharedAuthTokenService tokens = new SharedAuthTokenService(properties);
        SharedAuthCookieService cookies = new SharedAuthCookieService(properties, tokens);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setServerName("media.local"); request.setParameter("continue", "http://media.local:8081/gallery");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var authentication = UsernamePasswordAuthenticationToken.authenticated("viewer", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        new SharedAuthenticationSuccessHandler(cookies, properties).onAuthenticationSuccess(request, response, authentication);
        assertThat(response.getRedirectedUrl()).isEqualTo("http://media.local:8081/gallery");
        assertThat(response.getHeader("Set-Cookie")).contains("MEDIA_AUTH=").contains("Path=/").contains("HttpOnly").contains("SameSite=Lax");
    }
    @Test void rejectsExternalContinueRedirect() throws Exception {
        SharedAuthProperties properties = properties();
        SharedAuthCookieService cookies = new SharedAuthCookieService(properties, new SharedAuthTokenService(properties));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setServerName("media.local"); request.setParameter("continue", "https://attacker.example/steal");
        MockHttpServletResponse response = new MockHttpServletResponse();
        var authentication = UsernamePasswordAuthenticationToken.authenticated("viewer", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        new SharedAuthenticationSuccessHandler(cookies, properties).onAuthenticationSuccess(request, response, authentication);
        assertThat(response.getRedirectedUrl()).isEqualTo("/videos");
    }
    private static SharedAuthProperties properties() {
        SharedAuthProperties properties = new SharedAuthProperties();
        properties.setSecret("test-only-shared-auth-secret-32-characters-minimum");
        return properties;
    }
}
