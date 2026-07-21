package org.coryd.common.security;

/** Paths every service leaves open regardless of its own auth rules - health checks and API docs. */
public final class PublicPaths {

    private PublicPaths() {
    }

    public static final String[] OBSERVABILITY = {
            "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**"
    };
}
