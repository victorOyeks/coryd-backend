package org.coryd.common.instrumentation;

import jakarta.servlet.http.HttpServletRequest;
import org.coryd.common.security.CoRydUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Shared by LoggingInterceptor and RequestResponseLoggingFilter - same client-IP resolution and masked-user string, used by both the request-line logger and the full-body logger. */
final class RequestLoggingSupport {

    private static final String[] IP_HEADERS = {
            "X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP",
            "WL-Proxy-Client-IP", "HTTP_X_FORWARDED_FOR", "HTTP_X_FORWARDED",
            "HTTP_X_CLUSTER_CLIENT_IP", "HTTP_CLIENT_IP", "HTTP_FORWARDED_FOR",
            "HTTP_FORWARDED", "HTTP_VIA", "REMOTE_ADDR"
    };

    private RequestLoggingSupport() {
    }

    static String resolveUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CoRydUserDetails u) {
            return SensitiveDataMasker.maskPhone(u.getPhone()) + " " + u.getActiveRole();
        }
        return "anonymous";
    }

    static String getClientIp(HttpServletRequest request) {
        for (String header : IP_HEADERS) {
            String ip = request.getHeader(header);
            if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
                return ip.contains(",") ? ip.split(",")[0].trim() : ip;
            }
        }
        return request.getRemoteAddr();
    }
}
