package org.coryd.common.security.client;

import jakarta.servlet.http.HttpServletRequest;
import org.coryd.common.security.JwtUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;

/**
 * Forwards the inbound request's Authorization header onto outbound service-to-service
 * RestClient calls (e.g. trip-service -> profile-service/identity-service for enrichment
 * reads). Every service's SecurityConfig requires anyRequest().authenticated() and JwtFilter
 * only authenticates when a valid Bearer token is present - without this, an internal call
 * carries no token at all and gets a silent 401, degrading to null via the caller's own
 * try/catch. Reuses the same JWT the original rider/driver request already carried instead
 * of introducing a separate service-account or shared-secret auth path.
 *
 * <p>Calls made off the request thread - @Async pushes, schedulers - have no inbound header to
 * forward and used to send none at all, so enrichment came back empty and the caller quietly
 * served half-built data. Those fall back to a minted system token.
 */
public class ForwardedAuthInterceptor implements ClientHttpRequestInterceptor {

    private final SystemAuthInterceptor systemFallback;

    public ForwardedAuthInterceptor(JwtUtil jwtUtil) {
        this.systemFallback = new SystemAuthInterceptor(jwtUtil);
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        String authHeader = currentAuthorizationHeader();
        if (authHeader == null) {
            return systemFallback.intercept(request, body, execution);
        }
        request.getHeaders().set(HttpHeaders.AUTHORIZATION, authHeader);
        return execution.execute(request, body);
    }

    private String currentAuthorizationHeader() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest servletRequest = attrs.getRequest();
        return servletRequest.getHeader(HttpHeaders.AUTHORIZATION);
    }
}
