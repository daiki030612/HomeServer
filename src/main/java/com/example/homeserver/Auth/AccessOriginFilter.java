package com.example.homeserver.Auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

public class AccessOriginFilter extends OncePerRequestFilter {
    private final DirectAccessPolicy policy;
    public AccessOriginFilter(DirectAccessPolicy policy) { this.policy = policy; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!policy.isAllowed(request)) {
            // Do not dispatch to the protected /error page, which could start a login redirect.
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        chain.doFilter(request, response);
    }
}
