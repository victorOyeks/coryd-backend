package org.coryd.trip.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.trip.dto.DriverLocationRequest;
import org.coryd.trip.service.NearbyAlertService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The driver app sends where it is when it opens or comes back to the front, so the driver can be told riders
 * near them are going their way. Kept 30 minutes, one row per driver, never a trail. Always the caller's own.
 */
@RestController
@RequestMapping("/drivers/me")
@RequiredArgsConstructor
public class DriverPresenceController {

    private final NearbyAlertService nearbyAlertService;

    @PutMapping("/location")
    public ApiResponse<Void> location(@AuthenticationPrincipal CoRydUserDetails principal, @Valid @RequestBody DriverLocationRequest request) {
        nearbyAlertService.recordLocation(principal.getUserId(), request.lat(), request.lng());
        return ApiResponse.ok("Location noted", null);
    }

    /** Signing out, or turning the alerts off - forgets the location straight away. */
    @DeleteMapping("/location")
    public ApiResponse<Void> forget(@AuthenticationPrincipal CoRydUserDetails principal) {
        nearbyAlertService.forgetLocation(principal.getUserId());
        return ApiResponse.ok("Location cleared", null);
    }
}
