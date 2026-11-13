package org.coryd.payment.config;

import lombok.RequiredArgsConstructor;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.coryd.common.security.InternalOnlyPaths;
import org.coryd.common.security.filter.JwtFilter;
import org.coryd.common.security.filter.OpsPermissionFilter;
import org.coryd.common.security.OpsOwnedPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /**
     * /internal/** (InternalOnlyPaths.PAYMENT_SERVICE) is service-to-service only and needs the internal key on every
     * environment, like trip-, profile- and identity-service. It used to exist only with the dev tools on, which left
     * /internal/** open to any signed-in user (a rider included) wherever the dev tools are off.
     */
    @Bean
    public InternalApiKeyFilter internalApiKeyFilter(@Value("${services.internal.api-key}") String internalApiKey) {
        return new InternalApiKeyFilter(List.of(InternalOnlyPaths.PAYMENT_SERVICE), internalApiKey);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, InternalApiKeyFilter internalApiKeyFilter,
                                           ObjectMapper objectMapper,
                                           @Value("${services.internal.api-key}") String opsServiceKey) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/payments/webhook").permitAll()
                        .requestMatchers("/payments/clane/webhook").permitAll()
                        .requestMatchers(PublicPaths.OBSERVABILITY).permitAll()
                        .requestMatchers(PublicPaths.ERROR_DISPATCH).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(internalApiKeyFilter, JwtFilter.class)
                // Ops roles enforced here too: an ops-facing path needs the internal key (ops-service) or an ops role that
                // holds the permission (OpsOwnedPaths). Not a bean - a registered servlet filter would run before
                // authentication and mark itself done.
                .addFilterAfter(new OpsPermissionFilter(OpsOwnedPaths.PAYMENT_SERVICE, opsServiceKey, objectMapper), JwtFilter.class);

        return http.build();
    }
}
