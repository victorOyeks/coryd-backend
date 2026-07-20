package org.coryd.common.instrumentation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
public class LoggingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    private static final String[] IP_HEADERS = {
            "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP",
            "WL-Proxy-Client-IP", "HTTP_X_FORWARDED_FOR", "HTTP_X_FORWARDED",
            "HTTP_X_CLUSTER_CLIENT_IP", "HTTP_CLIENT_IP", "HTTP_FORWARDED_FOR",
            "HTTP_FORWARDED", "HTTP_VIA", "REMOTE_ADDR"
    };

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        MDC.put("reqId", UUID.randomUUID().toString());
        request.setAttribute("startTime", System.currentTimeMillis());

        String clientIp = getClientIp(request);
        String remoteAddr = request.getRemoteAddr();
        String user = resolveUser();

        if (clientIp.equals(remoteAddr)) {
            log.info(">>> {} {} from {} | user: {}",
                    request.getMethod(), request.getRequestURI(), clientIp, user);
        } else {
            log.info(">>> {} {} from {} (via proxy: {}) | user: {}",
                    request.getMethod(), request.getRequestURI(), clientIp, remoteAddr, user);
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        long elapsed = System.currentTimeMillis() - (Long) request.getAttribute("startTime");
        log.info("<<< {} {} -> {} ({}ms) | user: {}",
                request.getMethod(), request.getRequestURI(),
                response.getStatus(), elapsed, resolveUser());
        MDC.clear();
    }

    private String resolveUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CoRydUserDetails u) {
            return u.getPhone() + " " + u.getRoles();
        }
        return "anonymous";
    }

    private String getClientIp(HttpServletRequest request) {
        for (String header : IP_HEADERS) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                return ip.contains(",") ? ip.split(",")[0].trim() : ip;
            }
        }
        return request.getRemoteAddr();
    }
}
