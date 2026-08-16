package com.example.homeserver.Auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SharedAuthCookieFilter extends OncePerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(SharedAuthCookieFilter.class);

    private final SharedAuthCookieService cookies;
    private final SharedAuthTokenService tokens;
    private final SharedAuthProperties properties;
    private final AtomicBoolean configurationLogged = new AtomicBoolean();

    public SharedAuthCookieFilter(SharedAuthCookieService cookies, SharedAuthTokenService tokens,
            SharedAuthProperties properties) {
        this.cookies = cookies;
        this.tokens = tokens;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        logResolvedConfigurationOnce();

        boolean authenticationPresentBefore = SecurityContextHolder.getContext().getAuthentication() != null;
        Optional<String> cookie = cookies.read(request);
        logger.info("[AUTH_COOKIE] request method={} uri={} cookieReceived={} authenticationBefore={}",
                request.getMethod(), request.getRequestURI(), yesNo(cookie.isPresent()),
                presentAbsent(authenticationPresentBefore));

        boolean authenticationRestored = false;
        if (!authenticationPresentBefore && cookie.isPresent()) {
            SharedAuthTokenService.TokenVerification verification = tokens.diagnoseAndVerify(cookie.get());
            logVerification(verification);

            if (verification.identity().isPresent()) {
                SharedAuthTokenService.SharedIdentity identity = verification.identity().get();
                var authorities = identity.authorities().stream().map(SimpleGrantedAuthority::new).toList();
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(identity.username(), null, authorities));
                authenticationRestored = true;
            }
        } else if (cookie.isEmpty()) {
            logger.info("[AUTH_COOKIE] tokenParse=not-attempted reason=cookie-missing authenticationRestored=no");
        }

        logger.info("[AUTH_COOKIE] authenticationRestored={} securityContextBeforeChain={}",
                yesNo(authenticationRestored),
                presentAbsent(SecurityContextHolder.getContext().getAuthentication() != null));

        chain.doFilter(request, response);

        logger.info("[AUTH_COOKIE] securityContextAfterChain={} responseStatus={} uri={}",
                presentAbsent(SecurityContextHolder.getContext().getAuthentication() != null), response.getStatus(),
                request.getRequestURI());
    }

    private void logVerification(SharedAuthTokenService.TokenVerification verification) {
        logger.info(
                "[AUTH_COOKIE] tokenParse={} signatureValid={} tokenExpired={} expirationTime={} currentServerTime={} "
                        + "usernameExtracted={} authoritiesPresent={} userLookupPerformed=no authenticationRestoredCandidate={} "
                        + "result={}",
                successFailure(verification.parseSuccess()), yesNo(verification.signatureValid()),
                yesNo(verification.expired()), verification.expirationTime(), verification.currentTime(),
                yesNo(verification.usernameExtracted()), yesNo(verification.authoritiesPresent()),
                yesNo(verification.identity().isPresent()), verification.failureReason());
    }

    private void logResolvedConfigurationOnce() {
        if (!configurationLogged.compareAndSet(false, true)) {
            return;
        }
        logger.info(
                "[AUTH_COOKIE] resolvedConfiguration cookieName={} tokenTtl={} cookieSecure={} secretConfigured={} "
                        + "secretLengthValid={} userLookupPerformed=no",
                properties.getCookieName(), properties.getTokenTtl(), properties.isCookieSecure(),
                yesNo(properties.getSecret() != null && !properties.getSecret().isBlank()),
                yesNo(properties.getSecret() != null && properties.getSecret().length() >= 32));
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static String presentAbsent(boolean value) {
        return value ? "present" : "absent";
    }

    private static String successFailure(boolean value) {
        return value ? "success" : "failure";
    }
}
