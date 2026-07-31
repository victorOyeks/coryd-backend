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

/** Write (driver) is plain REST; the live-updating read path is the WebSocket topic /topic/bookings/{bookingId}/location - this GET is just the one-time "give me the last known point" fetch on screen-open. Both check the caller is actually a party to the trip, same as the WebSocket subscribe path. */
@RestController
@RequestMapping("/bookings")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @PostMapping("/{bookingId}/location")
    public ApiResponse<LocationResponse> record(@AuthenticationPrincipal CoRydUserDetails principal,
                                                 @PathVariable String bookingId, @Valid @RequestBody LocationUpdateRequest request) {
        return ApiResponse.ok(locationService.recordLocation(bookingId, principal.getUserId(), request.lat(), request.lng()));
    }

    @GetMapping("/{bookingId}/location")
    public ApiResponse<LocationResponse> get(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String bookingId) {
        return ApiResponse.ok(locationService.getLocationForParty(bookingId, principal.getUserId()));
    }
}
