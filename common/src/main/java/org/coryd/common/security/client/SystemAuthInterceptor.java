package org.coryd.common.security.client;

import org.coryd.common.enums.UserRole;
import org.coryd.common.security.JwtUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Mints a short-lived OPS_ADMIN token for service-to-service calls that originate from a
 * scheduler rather than a user request - ForwardedAuthInterceptor has no inbound header to
 * forward there. Signed with the shared jwt.secret, so every service validates it normally.
 */
public class SystemAuthInterceptor implements ClientHttpRequestInterceptor {

    private static final String SYSTEM_USER_ID = "system";
    private static final Duration REFRESH_MARGIN = Duration.ofMinutes(5);

    private final JwtUtil jwtUtil;

    private volatile String cachedToken;
    private volatile Instant cachedUntil = Instant.EPOCH;

    public SystemAuthInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, "Bearer " + token());
        return execution.execute(request, body);
    }

    private String token() {
        if (cachedToken == null || Instant.now().isAfter(cachedUntil)) {
            cachedToken = jwtUtil.generateToken(SYSTEM_USER_ID, null, "CoRyd System", UserRole.OPS_ADMIN);
            cachedUntil = Instant.now()
                    .plusMillis(jwtUtil.getExpirationMs())
                    .minus(REFRESH_MARGIN);
        }
        return cachedToken;
    }
}
