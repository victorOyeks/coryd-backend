package org.coryd.common.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.OpsPermission;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.security.OpsPermissions;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Ops roles enforced in the service that owns the data, not only in ops-service. Some ops-facing endpoints (profile-service's
 * verification queue and decisions, payment-service's earnings and payout accounts) sit on public paths the gateway
 * reaches, and used to accept any OPS_ADMIN token - a FINANCE admin could approve a driver by calling profile-service
 * directly. Each service builds one of these with its own rule table (first match wins); for a matching request by an
 * OPS_ADMIN principal it lets through:
 * <ul>
 *   <li>a call carrying the internal API key - ops-service, which already enforced OpsAccessPolicy for the admin; or</li>
 *   <li>a token whose ops role holds the rule's permission ({@link OpsPermissions}).</li>
 * </ul>
 * Everything else gets {@code 403 Your ops role (X) can't …}. Riders and drivers are never affected here (their own
 * self-only checks still apply in the services), and paths without a rule are untouched. Runs after JwtFilter.
 */
@Slf4j
public class OpsPermissionFilter extends OncePerRequestFilter {

    /** method null = any method. permission {@link OpsPermissions#SUPER_ADMIN_ONLY} (null) = super admins only. */
    public record Rule(String method, String pattern, OpsPermission permission) {
        public static Rule get(String pattern, OpsPermission permission) {
            return new Rule("GET", pattern, permission);
        }

        public static Rule any(String pattern, OpsPermission permission) {
            return new Rule(null, pattern, permission);
        }

        boolean matches(String requestMethod, String path) {
            return (method == null || method.equalsIgnoreCase(requestMethod)) && MATCHER.match(pattern, path);
        }
    }

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final List<Rule> rules;
    private final byte[] internalApiKey;
    private final ObjectMapper objectMapper;

    public OpsPermissionFilter(List<Rule> rules, String internalApiKey, ObjectMapper objectMapper) {
        this.rules = List.copyOf(rules);
        this.internalApiKey = internalApiKey == null ? new byte[0] : internalApiKey.getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    public List<Rule> rules() {
        return rules;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CoRydUserDetails principal && !fromOpsService(request)) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            Rule rule = rules.stream().filter(r -> r.matches(request.getMethod(), path)).findFirst().orElse(null);
            String refusal = rule == null ? null : OpsPermissions.refusal(principal, rule.permission());
            if (refusal != null) {
                log.warn("Refused {} {} for ops admin {} ({}): {}", request.getMethod(), path, principal.getUserId(),
                        principal.getOpsRole(), refusal);
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(refusal)));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean fromOpsService(HttpServletRequest request) {
        String provided = request.getHeader(InternalApiKeyFilter.HEADER);
        return provided != null && internalApiKey.length > 0
                && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), internalApiKey);
    }
}
