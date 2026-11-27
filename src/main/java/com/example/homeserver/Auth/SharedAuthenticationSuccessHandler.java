package com.example.homeserver.Auth;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.*;

public class SharedAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
    private final SharedAuthCookieService cookies;
    private final SharedAuthProperties properties;
    private final DirectAccessPolicy routes;
    private final RequestCache requestCache;

    public SharedAuthenticationSuccessHandler(SharedAuthCookieService cookies, SharedAuthProperties properties) {
        this(cookies, properties, null, new HttpSessionRequestCache());
    }
    public SharedAuthenticationSuccessHandler(SharedAuthCookieService cookies, SharedAuthProperties properties,
            DirectAccessPolicy routes, RequestCache requestCache) {
        this.cookies = cookies;
        this.properties = properties;
        this.routes = routes;
        this.requestCache = requestCache;
    }
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        boolean direct = routes != null && routes.isDirect(request);
        if (!direct) cookies.issue(request, response, authentication);
        SavedRequest saved = requestCache.getRequest(request, response);
        String destination = request.getContextPath() + "/videos";
        if (!direct && request.getParameter("continue") != null && !request.getParameter("continue").isBlank()) {
            destination = safeContinue(request, destination);
        } else if (saved != null) {
            // Discard the saved authority to keep redirects on the current access route.
            URI uri = URI.create(saved.getRedirectUrl());
            destination = safeRelative(uri.getRawPath()
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()), destination);
        }
        requestCache.removeRequest(request, response);
        response.sendRedirect(destination);
    }
    private String safeContinue(HttpServletRequest request, String fallback) {
        String candidate = request.getParameter("continue");
        if (candidate == null || candidate.isBlank()) return fallback;
        if (candidate.startsWith("/")) return safeRelative(candidate, fallback);
        try {
            URI uri = URI.create(candidate);
            if (uri.getUserInfo() != null || uri.getHost() == null || unsafeCharacters(candidate)
                    || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) return fallback;
            if (!properties.getImageUrl().isBlank()) {
                URI image = URI.create(properties.getImageUrl());
                return sameOrigin(uri, image) ? candidate : fallback;
            }
            // Never derive an external redirect allowlist from the request's Host header.
            return fallback;
        } catch (RuntimeException exception) { return fallback; }
    }
    private static String safeRelative(String candidate, String fallback) {
        try {
            URI uri = URI.create(candidate);
            return candidate.startsWith("/") && !candidate.startsWith("//")
                    && !unsafeCharacters(candidate) && uri.getRawAuthority() == null && !uri.isAbsolute()
                    ? candidate : fallback;
        } catch (RuntimeException exception) { return fallback; }
    }
    private static boolean unsafeCharacters(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return value.chars().anyMatch(c -> c <= 32 || c == 127 || c == '\\')
                || lower.contains("%5c") || lower.contains("%0d") || lower.contains("%0a")
                || lower.startsWith("/%2f");
    }
    private static boolean sameOrigin(URI a, URI b) {
        return a.getHost().equalsIgnoreCase(b.getHost()) && a.getScheme().equalsIgnoreCase(b.getScheme())
                && port(a) == port(b);
    }
    private static int port(URI uri) {
        return uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }
}
