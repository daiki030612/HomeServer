package com.example.homeserver.Auth;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
public class SharedAuthenticationSuccessHandler implements AuthenticationSuccessHandler {
    private final SharedAuthCookieService cookies; private final SharedAuthProperties properties;
    public SharedAuthenticationSuccessHandler(SharedAuthCookieService cookies,SharedAuthProperties properties){this.cookies=cookies;this.properties=properties;}
    @Override public void onAuthenticationSuccess(HttpServletRequest request,HttpServletResponse response,Authentication authentication)throws IOException{cookies.issue(request,response,authentication);response.sendRedirect(safeDestination(request));}
    private String safeDestination(HttpServletRequest request){String candidate=request.getParameter("continue");String fallback=request.getContextPath()+"/videos";if(candidate==null||candidate.isBlank())return fallback;if(candidate.startsWith("/")&&!candidate.startsWith("//"))return candidate;try{URI uri=URI.create(candidate);if(!("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))||!allowedHost(uri,request)||!allowedPort(uri))return fallback;return uri.toString();}catch(RuntimeException exception){return fallback;}}
    private boolean allowedHost(URI uri,HttpServletRequest request){if(uri.getHost()!=null&&uri.getHost().equalsIgnoreCase(request.getServerName()))return true;if(properties.getImageUrl().isBlank())return false;try{return uri.getHost()!=null&&uri.getHost().equalsIgnoreCase(URI.create(properties.getImageUrl()).getHost());}catch(RuntimeException exception){return false;}}
    private boolean allowedPort(URI uri){int port=port(uri);if(port==properties.getImagePort())return true;if(properties.getImageUrl().isBlank())return false;return port==port(URI.create(properties.getImageUrl()));}
    private static int port(URI uri){return uri.getPort()==-1?("https".equals(uri.getScheme().toLowerCase(Locale.ROOT))?443:80):uri.getPort();}
}
