package com.example.homeserver.Auth;
import jakarta.servlet.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
public class SharedAuthCookieService {
    private final SharedAuthProperties properties; private final SharedAuthTokenService tokens;
    public SharedAuthCookieService(SharedAuthProperties properties,SharedAuthTokenService tokens){this.properties=properties;this.tokens=tokens;}
    public Optional<String> read(HttpServletRequest request){if(request.getCookies()==null)return Optional.empty();return Arrays.stream(request.getCookies()).filter(cookie->properties.getCookieName().equals(cookie.getName())).map(Cookie::getValue).findFirst();}
    public void issue(HttpServletRequest request,HttpServletResponse response,Authentication authentication){List<String> roles=authentication.getAuthorities().stream().map(a->a.getAuthority()).toList();write(request,response,tokens.create(authentication.getName(),roles),properties.getTokenTtl());}
    public void clear(HttpServletRequest request,HttpServletResponse response){write(request,response,"",Duration.ZERO);}
    private void write(HttpServletRequest request,HttpServletResponse response,String value,Duration maxAge){ResponseCookie cookie=ResponseCookie.from(properties.getCookieName(),value).httpOnly(true).secure(properties.isCookieSecure()||request.isSecure()).sameSite("Lax").path("/").maxAge(maxAge).build();response.addHeader(HttpHeaders.SET_COOKIE,cookie.toString());}
}
