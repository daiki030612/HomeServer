package com.example.homeserver.Config;

import com.example.homeserver.Auth.SharedAuthCookieFilter;
import com.example.homeserver.Auth.SharedAuthCookieService;
import com.example.homeserver.Auth.SharedAuthenticationSuccessHandler;
import com.example.homeserver.Auth.SharedAuthProperties;
import com.example.homeserver.Auth.SharedAuthTokenService;
import com.example.homeserver.Auth.DirectAccessPolicy;
import com.example.homeserver.Auth.AccessOriginFilter;
import com.example.homeserver.Auth.RouteSecurityContextRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SharedAuthProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SharedAuthCookieService cookies,
            SharedAuthTokenService tokens, SharedAuthProperties properties, DirectAccessPolicy routes) throws Exception {
        var requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(request -> "GET".equals(request.getMethod())
                && !request.getRequestURI().equals(request.getContextPath() + "/login"));
        requestCache.setMatchingRequestParameterName(null);
        http
            .authorizeHttpRequests((requests) -> requests
                .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin((form) -> form
                .loginPage("/login")
                .successHandler(new SharedAuthenticationSuccessHandler(cookies, properties, routes, requestCache))
                .permitAll()
            )
            .logout((logout) -> logout
                .addLogoutHandler((request, response, authentication) -> {
                    if (!routes.isDirect(request)) cookies.clear(request, response);
                })
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            )
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(
                    (request, response, exception) -> response.sendRedirect(request.getContextPath() + "/login")))
            .requestCache(cache -> cache.requestCache(requestCache))
            .securityContext(context -> context.securityContextRepository(new RouteSecurityContextRepository(routes)))
            .addFilterBefore(new AccessOriginFilter(routes), DisableEncodeUrlFilter.class)
            .addFilterBefore(new SharedAuthCookieFilter(cookies, tokens, properties, routes),
                    UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public DirectAccessPolicy directAccessPolicy(
            @Value("${video-auth.direct-origin:}") String directOrigin,
            @Value("${navigation.video.url:}") String publicOrigin) {
        return new DirectAccessPolicy(directOrigin, publicOrigin);
    }

    @Bean
    public SharedAuthTokenService sharedAuthTokenService(SharedAuthProperties properties) {
        return new SharedAuthTokenService(properties);
    }

    @Bean
    public SharedAuthCookieService sharedAuthCookieService(SharedAuthProperties properties, SharedAuthTokenService tokens) {
        return new SharedAuthCookieService(properties, tokens);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
