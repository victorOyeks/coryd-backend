package org.coryd.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Second, independent gate on top of the normal JWT check (JwtFilter) - runs first, so a
 * missing/wrong key on a gated path short-circuits before any JWT work. Each service constructs
 * its own instance with its own path list (see InternalOnlyPaths) - this isn't a shared
 * singleton bean since the gated paths differ per service.
 *
 * "/me" paths (self-lookups, e.g. /drivers/me) are never gated regardless of pattern overlap -
 * a glob like "/drivers/*" would otherwise also match "/drivers/me", which is mobile's own
 * profile call and must stay JWT-only.
 */
@Slf4j
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";

    private static final Pattern ME_SEGMENT = Pattern.compile("(^|/)me(/|$)");
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final List<String> internalOnlyPatterns;
    private final String expectedKey;

    public InternalApiKeyFilter(List<String> internalOnlyPatterns, String expectedKey) {
        this.internalOnlyPatterns = internalOnlyPatterns;
        this.expectedKey = expectedKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getServletPath();

        if (!ME_SEGMENT.matcher(path).find() && matchesInternalOnlyPattern(path)) {
            String providedKey = request.getHeader(HEADER);
            if (providedKey == null || !MessageDigest.isEqual(
                    providedKey.getBytes(StandardCharsets.UTF_8), expectedKey.getBytes(StandardCharsets.UTF_8))) {
                log.warn("Rejected request to internal-only path {} - missing or invalid {}", path, HEADER);
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "This endpoint is for internal service use only");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean matchesInternalOnlyPattern(String path) {
        return internalOnlyPatterns.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }
}
