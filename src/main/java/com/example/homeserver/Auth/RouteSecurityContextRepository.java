package com.example.homeserver.Auth;

import jakarta.servlet.http.*;
import org.springframework.security.core.context.*;
import org.springframework.security.web.context.*;

/** Shared tokens remain request-only; direct logins use a separate session context. */
public class RouteSecurityContextRepository implements SecurityContextRepository {
    private final DirectAccessPolicy policy;
    private final SecurityContextRepository shared = new RequestAttributeSecurityContextRepository();
    private final HttpSessionSecurityContextRepository direct = new HttpSessionSecurityContextRepository();

    public RouteSecurityContextRepository(DirectAccessPolicy policy) {
        this.policy = policy;
        direct.setSpringSecurityContextKey("VIDEO_DIRECT_SECURITY_CONTEXT");
        direct.setDisableUrlRewriting(true);
    }
    private SecurityContextRepository repository(HttpServletRequest request) {
        return policy.isDirect(request) ? direct : shared;
    }
    @Override public SecurityContext loadContext(HttpRequestResponseHolder holder) {
        return repository(holder.getRequest()).loadContext(holder);
    }
    @Override public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return repository(request).loadDeferredContext(request);
    }
    @Override public boolean containsContext(HttpServletRequest request) {
        return repository(request).containsContext(request);
    }
    @Override public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        repository(request).saveContext(context, request, response);
    }
}
