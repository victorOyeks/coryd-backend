package org.coryd.payment.config;

import lombok.RequiredArgsConstructor;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.coryd.common.security.InternalOnlyPaths;
import org.coryd.common.security.filter.JwtFilter;
import org.coryd.common.security.PublicPaths;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
     * Only /dev/** is internal-only here, so the filter exists only when the dev tools do.
     * Keeping the key injection on this method rather than a field means payment-service still
     * boots without INTERNAL_API_KEY set whenever the dev tools are off.
     */
    @Bean
    @ConditionalOnProperty(name = "coryd.dev-tools.enabled", havingValue = "true")
    public InternalApiKeyFilter internalApiKeyFilter(@Value("${services.internal.api-key}") String internalApiKey) {
        return new InternalApiKeyFilter(List.of(InternalOnlyPaths.PAYMENT_SERVICE), internalApiKey);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectProvider<InternalApiKeyFilter> internalApiKeyFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/payments/webhook").permitAll()
                        .requestMatchers(PublicPaths.OBSERVABILITY).permitAll()
                        .requestMatchers(PublicPaths.ERROR_DISPATCH).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        InternalApiKeyFilter devGate = internalApiKeyFilter.getIfAvailable();
        if (devGate != null) {
            http.addFilterBefore(devGate, JwtFilter.class);
        }

        return http.build();
    }
}
