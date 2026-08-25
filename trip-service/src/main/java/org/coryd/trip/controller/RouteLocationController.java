package org.coryd.trip.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.trip.dto.LocationResponse;
import org.coryd.trip.dto.LocationUpdateRequest;
import org.coryd.trip.service.LocationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Route-scoped counterpart to LocationController - the Live Trip map's driver posts one shared point per route instead of one per passenger booking. Live-updating read path is the WebSocket topic /topic/routes/{routePostId}/location; this GET is the one-time fetch on screen-open. */
@RestController
@RequestMapping("/routes")
@RequiredArgsConstructor
public class RouteLocationController {

    private final LocationService locationService;

    @PostMapping("/{routePostId}/location")
    public ApiResponse<LocationResponse> record(@AuthenticationPrincipal CoRydUserDetails principal,
                                                 @PathVariable String routePostId, @Valid @RequestBody LocationUpdateRequest request) {
        return ApiResponse.ok(locationService.recordLocationForRoute(routePostId, principal.getUserId(), request.lat(), request.lng()));
    }

    @GetMapping("/{routePostId}/location")
    public ApiResponse<LocationResponse> get(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String routePostId) {
        return ApiResponse.ok(locationService.getLocationForRoute(routePostId, principal.getUserId()));
    }
}
