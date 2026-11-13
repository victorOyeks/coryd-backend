package org.coryd.ops.access;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.coryd.common.enums.UserRole;
import org.coryd.common.security.CoRydUserDetails;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Enforces OpsAccessPolicy on every console request after SecurityConfig has let an OPS_ADMIN in. Requests with no
 * OPS_ADMIN principal pass untouched - SecurityConfig refused them already, or (GET /stream) the controller checks its own
 * stream token.
 */
@Configuration
public class OpsAccessInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CoRydUserDetails admin) || admin.getActiveRole() != UserRole.OPS_ADMIN) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        OpsAccess.require(admin, OpsAccessPolicy.required(request.getMethod(), path));
        return true;
    }
}
