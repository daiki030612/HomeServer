package com.example.homeserver.Config;

import com.example.homeserver.Auth.SharedAuthCookieFilter;
import com.example.homeserver.Auth.SharedAuthCookieService;
import com.example.homeserver.Auth.SharedAuthenticationSuccessHandler;
import com.example.homeserver.Auth.SharedAuthProperties;
import com.example.homeserver.Auth.SharedAuthTokenService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SharedAuthProperties.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, SharedAuthCookieService cookies,
            SharedAuthTokenService tokens, SharedAuthProperties properties) throws Exception {
        http
            .authorizeHttpRequests((requests) -> requests
                .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin((form) -> form
                .loginPage("/login")
                .successHandler(new SharedAuthenticationSuccessHandler(cookies, properties))
                .permitAll()
            )
            .logout((logout) -> logout
                .addLogoutHandler((request, response, authentication) -> cookies.clear(request, response))
                .logoutSuccessUrl("/login?logout")
                .permitAll()
            )
            .requestCache(cache -> cache.disable())
            .securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
            .addFilterBefore(new SharedAuthCookieFilter(cookies, tokens), UsernamePasswordAuthenticationFilter.class);

        return http.build();
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
