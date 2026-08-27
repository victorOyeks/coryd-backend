package org.coryd.common.security;

/** Paths every service leaves open regardless of its own auth rules - health checks and API docs. */
public final class PublicPaths {

    private PublicPaths() {
    }

    public static final String[] OBSERVABILITY = {
            "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**"
    };

    /**
     * The container's ERROR dispatch target. Spring Boot runs the security filter chain on the
     * ERROR dispatch too, but by then the SecurityContext has been cleared and JwtFilter (an
     * OncePerRequestFilter) skips error dispatches - so gating /error rewrites every unhandled
     * 500 into a misleading 401 before the error body is ever rendered.
     */
    public static final String[] ERROR_DISPATCH = {"/error"};
}
