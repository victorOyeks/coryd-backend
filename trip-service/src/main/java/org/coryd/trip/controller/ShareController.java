package org.coryd.trip.controller;

import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.trip.dto.SharedTripResponse;
import org.coryd.trip.service.BookingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, unauthenticated - see SecurityConfig's /shared/** permitAll. Whoever gets the SMS link has no CoRyd account. */
@RestController
@RequestMapping("/shared")
@RequiredArgsConstructor
public class ShareController {

    private final BookingService bookingService;

    @GetMapping("/{token}")
    public ApiResponse<SharedTripResponse> view(@PathVariable String token) {
        return ApiResponse.ok(bookingService.getSharedTripView(token));
    }
}
