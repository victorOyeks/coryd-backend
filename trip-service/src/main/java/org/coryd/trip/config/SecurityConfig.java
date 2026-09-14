package org.coryd.trip.config;

import lombok.RequiredArgsConstructor;
import org.coryd.common.security.InternalApiKeyFilter;
import org.coryd.common.security.InternalOnlyPaths;
import org.coryd.common.security.JwtFilter;
import org.coryd.common.security.PublicPaths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final AuthenticationEntryPoint authenticationEntryPoint;
    private final AccessDeniedHandler accessDeniedHandler;

    @Value("${services.internal.api-key}")
    private String internalApiKey;

    @Bean
    public InternalApiKeyFilter internalApiKeyFilter() {
        return new InternalApiKeyFilter(List.of(InternalOnlyPaths.TRIP_SERVICE), internalApiKey);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, InternalApiKeyFilter internalApiKeyFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PublicPaths.OBSERVABILITY).permitAll()
                        .requestMatchers(PublicPaths.ERROR_DISPATCH).permitAll()
                        // Real auth for the socket happens inside the STOMP CONNECT frame
                        // (WebSocketAuthInterceptor), not at this HTTP-layer handshake - Spring
                        // Security still gates the initial handshake request otherwise. Both
                        // endpoints need this: /ws (SockJS, browser) and /ws-native (plain
                        // WebSocket upgrade, mobile) - the native upgrade request carries no
                        // Authorization header at all, so leaving it off here 401s the upgrade
                        // itself before a single STOMP frame is ever sent.
                        .requestMatchers("/ws/**", "/ws-native/**").permitAll()
                        // Read-only public trip-share link - the whole point is no login (see ShareController).
                        .requestMatchers("/shared/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(internalApiKeyFilter, JwtFilter.class);

        return http.build();
    }
}
