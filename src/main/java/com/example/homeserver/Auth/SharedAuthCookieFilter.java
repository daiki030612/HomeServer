package com.example.homeserver.Auth;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
public class SharedAuthCookieFilter extends OncePerRequestFilter {
    private final SharedAuthCookieService cookies; private final SharedAuthTokenService tokens;
    public SharedAuthCookieFilter(SharedAuthCookieService cookies,SharedAuthTokenService tokens){this.cookies=cookies;this.tokens=tokens;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        if(SecurityContextHolder.getContext().getAuthentication()==null)cookies.read(request).flatMap(tokens::verify).ifPresent(identity->{var authorities=identity.authorities().stream().map(SimpleGrantedAuthority::new).toList();SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity.username(),null,authorities));});
        chain.doFilter(request,response);
    }
}
