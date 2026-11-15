package org.coryd.common.instrumentation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.coryd.common.security.filter.InternalApiKeyFilter;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logs each request and response with its body, masked (SensitiveDataMasker). A logged body is at most
 * {@code coryd.logging.max-body-chars} characters (2048 by default; 0 for no limit) - the masking is streamed and stops
 * there, so a large page costs no more to log than a small one. Calls from another CoRyd service (carrying this service's
 * internal API key) aren't body-logged at all: the calling service logs its own request, and LoggingInterceptor still
 * logs the line, status and time here.
 */
@Component
public class RequestResponseLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);

    public static final int DEFAULT_MAX_BODY_CHARS = 2048;

    private final String internalApiKey;
    private final int maxBodyChars;

    public RequestResponseLoggingFilter() {
        this("", DEFAULT_MAX_BODY_CHARS);
    }

    @Autowired
    public RequestResponseLoggingFilter(@Value("${services.internal.api-key:}") String internalApiKey,
                                        @Value("${coryd.logging.max-body-chars:" + DEFAULT_MAX_BODY_CHARS + "}") int maxBodyChars) {
        this.internalApiKey = internalApiKey;
        this.maxBodyChars = maxBodyChars;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        // A Server-Sent Events stream stays open for minutes: wrapped in a caching response it would never reach the browser.
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/event-stream")) {
            return true;
        }
        // A CSV download (reports, the audit export) runs to megabytes: cached, masked and logged whole, it reached the caller
        // only after all of that - seconds per file, twice when ops-service relays trip-service's. LoggingInterceptor still
        // logs the request line and status.
        if (path.endsWith(".csv") || path.endsWith("/export")) {
            return true;
        }
        // Service-to-service: the audit export alone is ~80 such calls of 500 rows each, every one held, masked and logged
        // whole before it left. The key is checked, not just present - a made-up header doesn't switch the body log off.
        if (InternalApiKeyFilter.hasValidKey(request, internalApiKey)) {
            return true;
        }
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

    /** Webhooks whose body is someone's identity record (name, date of birth, address, location) or payment details - never logged.
     * The request line and the outcome still are, and the parts we keep are on the check's own record. */
    private static final List<String> UNLOGGED_BODY_PATHS = List.of("/webhooks/smile-id", "/payments/clane/webhook");

    private void logRequest(ContentCachingRequestWrapper request) {
        String maskedBody = UNLOGGED_BODY_PATHS.stream().anyMatch(request.getRequestURI()::contains)
                ? "<not logged: identity or payment details>"
                : SensitiveDataMasker.maskBody(request.getContentAsByteArray(), maxBodyChars);

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
        String contentType = response.getContentType();
        if (contentType != null && contentType.startsWith("text/html")) {
            log.info("RESPONSE {} | body: <html page>", response.getStatus());
            return;
        }
        String maskedBody = SensitiveDataMasker.maskBody(response.getContentAsByteArray(), maxBodyChars);

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
