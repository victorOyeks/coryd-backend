package org.coryd.common.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.revocation.TokenRevocationRegistry;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Set on the request when a genuine token was refused because its sessions were ended (suspension, sign out everywhere).
     * The request carries on unauthenticated - a public endpoint (sign-in) still works - and the 401 for a protected one says
     * why (SecurityBeans' entry point reads it).
     */
    public static final String REFUSAL_ATTRIBUTE = "coryd.auth.refusal";

    private final JwtUtil jwtUtil;
    private final TokenRevocationRegistry revocations;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);

        Claims claims = StringUtils.hasText(token) ? jwtUtil.validClaims(token) : null;
        if (claims != null) {
            String refusal = revocations.refusal(claims.getSubject(), JwtUtil.issuedAtMillis(claims));
            if (refusal != null) {
                request.setAttribute(REFUSAL_ATTRIBUTE, refusal);
            } else {
                CoRydUserDetails userDetails = jwtUtil.userDetails(claims);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities()
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
