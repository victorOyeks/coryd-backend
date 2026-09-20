package org.coryd.common.instrumentation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
public class LoggingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        MDC.put("reqId", UUID.randomUUID().toString());
        request.setAttribute("startTime", System.currentTimeMillis());

        String clientIp = RequestLoggingSupport.getClientIp(request);
        String remoteAddr = request.getRemoteAddr();
        String user = RequestLoggingSupport.resolveUser();

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
        Object startTime = request.getAttribute("startTime");
        long elapsed = startTime instanceof Long start ? System.currentTimeMillis() - start : -1L;
        int status = resolveStatus(response, ex);
        String user = RequestLoggingSupport.resolveUser();

        if (status >= 500) {
            log.error("<<< {} {} -> {} ({}ms) | user: {}{}",
                    request.getMethod(), request.getRequestURI(), status, elapsed, user,
                    ex == null ? "" : " | " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } else if (status >= 400) {
            log.warn("<<< {} {} -> {} ({}ms) | user: {}",
                    request.getMethod(), request.getRequestURI(), status, elapsed, user);
        } else {
            log.info("<<< {} {} -> {} ({}ms) | user: {}",
                    request.getMethod(), request.getRequestURI(), status, elapsed, user);
        }
        MDC.clear();
    }

    /**
     * Tomcat only stamps 500 on the response after the servlet rethrows, which is later than
     * afterCompletion runs - so an unhandled exception would otherwise be logged as the 200
     * the handler had optimistically set, hiding real failures from error-rate monitoring.
     */
    private int resolveStatus(HttpServletResponse response, Exception ex) {
        int status = response.getStatus();
        if (ex != null && status < HttpServletResponse.SC_BAD_REQUEST) {
            return HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        }
        return status;
    }

}
