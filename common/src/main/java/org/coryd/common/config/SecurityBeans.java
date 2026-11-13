package org.coryd.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.filter.JwtFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
public class SecurityBeans {

    /**
     * Every service authenticates via JwtFilter, never via Spring Security's own
     * AuthenticationManager - but its mere absence makes Spring Boot's
     * UserDetailsServiceAutoConfiguration generate an in-memory user + log a random
     * password on every startup. Declaring this bean satisfies that auto-config's
     * missing-bean condition and silences it; it is never actually invoked.
     */
    @Bean
    public AuthenticationManager authenticationManager() {
        return authentication -> {
            throw new UnsupportedOperationException("Stateless JWT auth - AuthenticationManager is not used");
        };
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, authException) -> {
            // A genuine token whose sessions ops ended: say so, so the app can sign the person out with the reason.
            Object refusal = request.getAttribute(JwtFilter.REFUSAL_ATTRIBUTE);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    objectMapper.writeValueAsString(ApiResponse.error(refusal instanceof String message
                            ? message : "Unauthorized: " + authException.getMessage()))
            );
        };
    }

    @Bean
    public AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, accessDeniedException) -> {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    objectMapper.writeValueAsString(ApiResponse.error("Access denied: insufficient permissions"))
            );
        };
    }
}
