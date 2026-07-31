package org.coryd.common.security;

import jakarta.servlet.http.HttpServletRequest;
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
 */
public class ForwardedAuthInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        String authHeader = currentAuthorizationHeader();
        if (authHeader != null) {
            request.getHeaders().set(HttpHeaders.AUTHORIZATION, authHeader);
        }
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
