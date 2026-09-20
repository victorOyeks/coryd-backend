package org.coryd.common.instrumentation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/actuator") || path.contains("/ws/") || path.endsWith("/ws")
                || path.contains("/swagger-ui") || path.contains("/v3/api-docs") || path.contains("/webjars");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        ContentCachingRequestWrapper req = new ContentCachingRequestWrapper(request, 4096);
        ContentCachingResponseWrapper res = new ContentCachingResponseWrapper(response);

        try {
            chain.doFilter(req, res);
        } finally {
            // copyBodyToResponse has to run even when the handler threw, or the buffered
            // body is discarded and the caller gets an empty response alongside the 500.
            logRequest(req);
            logResponse(res);
            res.copyBodyToResponse();
        }
    }

    private void logRequest(ContentCachingRequestWrapper request) {
        String body = new String(request.getContentAsByteArray(), StandardCharsets.UTF_8);
        String maskedBody = SensitiveDataMasker.maskJsonBody(body);

        String clientIp = RequestLoggingSupport.getClientIp(request);
        String remoteAddr = request.getRemoteAddr();
        String clientInfo = clientIp.equals(remoteAddr) ? clientIp : clientIp + " (via proxy: " + remoteAddr + ")";

        log.info("REQUEST  {} {} | from: {} | user: {}\nHeaders: {}\nBody:\n{}",
                request.getMethod(),
                request.getRequestURI(),
                clientInfo,
                RequestLoggingSupport.resolveUser(),
                getMaskedHeaders(request),
                maskedBody.isBlank() ? "<empty>" : maskedBody);
    }

    private void logResponse(ContentCachingResponseWrapper response) {
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        String maskedBody = SensitiveDataMasker.maskJsonBody(body);

        log.info("RESPONSE {} | body:\n{}",
                response.getStatus(),
                maskedBody.isBlank() ? "<empty>" : maskedBody);
    }

    private Map<String, String> getMaskedHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(request.getHeaderNames())) {
            headers.put(name, SensitiveDataMasker.maskHeaderValue(name, request.getHeader(name)));
        }
        return headers;
    }

}
