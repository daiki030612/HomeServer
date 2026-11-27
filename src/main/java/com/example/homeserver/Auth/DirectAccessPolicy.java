package com.example.homeserver.Auth;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;

/** Direct access must match both the configured origin and the receiving interface. */
public class DirectAccessPolicy {
    private final URI origin;
    private final URI publicOrigin;

    public DirectAccessPolicy(String directOrigin, String publicOrigin) {
        this.origin = directOrigin.isBlank() ? null : parseOrigin(directOrigin);
        this.publicOrigin = publicOrigin.isBlank() ? null : parseOrigin(publicOrigin);
    }

    private static URI parseOrigin(String value) {
        URI uri = URI.create(value);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid configured authentication origin");
        }
        return uri;
    }

    public boolean isDirect(HttpServletRequest request) {
        if (origin == null) return false;
        String path = origin.getPath();
        String contextPath = path == null || path.equals("/") ? "" : path;
        return origin.getHost().equals(request.getLocalAddr()) && matches(origin, request)
                && contextPath.equals(request.getContextPath());
    }

    public boolean isAllowed(HttpServletRequest request) {
        boolean directInterface = origin != null && origin.getHost().equals(request.getLocalAddr());
        boolean directHost = origin != null && origin.getHost().equalsIgnoreCase(request.getServerName());
        if (directInterface || directHost) {
            return isDirect(request) && java.util.Collections.list(request.getHeaderNames()).stream()
                    .noneMatch(name -> name.equalsIgnoreCase("Forwarded")
                            || name.toLowerCase(java.util.Locale.ROOT).startsWith("x-forwarded-"));
        }
        return publicOrigin == null || matches(publicOrigin, request);
    }

    private static boolean matches(URI origin, HttpServletRequest request) {
        int port = origin.getPort() == -1 ? ("https".equals(origin.getScheme()) ? 443 : 80) : origin.getPort();
        return origin.getScheme().equalsIgnoreCase(request.getScheme())
                && origin.getHost().equalsIgnoreCase(request.getServerName()) && port == request.getServerPort();
    }

}
