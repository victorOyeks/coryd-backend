package org.coryd.trip.dev;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only test tooling. Registered only when coryd.dev-tools.enabled is true, so on any
 * environment that leaves the flag off these paths 404 as if they were never written.
 * Still behind the normal JWT check (SecurityConfig's anyRequest().authenticated()).
 */
@RestController
@RequestMapping("/dev")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coryd.dev-tools.enabled", havingValue = "true")
public class DevToolsController {

    private final RouteResetService routeResetService;

    /** Back to just-posted: PUBLISHED, all seats free, bookings and their payment rows gone. */
    @PostMapping("/routes/{routePostId}/reset")
    public ApiResponse<RouteResetResult> resetRoute(@PathVariable String routePostId) {
        return ApiResponse.ok("Route reset to initial state", routeResetService.reset(routePostId));
    }
}
