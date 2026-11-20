package org.coryd.trip.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.trip.dto.FirstTripDtos;
import org.coryd.trip.entity.FirstTripExperience;
import org.coryd.trip.service.FirstTripService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/** First trip celebration - always the signed-in person's own, from the JWT. role is DRIVER or RIDER. */
@RestController
@RequestMapping("/me/first-trip")
@RequiredArgsConstructor
public class FirstTripController {

    private final FirstTripService firstTripService;

    /** "Should I celebrate?" - the trip to celebrate, or null. */
    @GetMapping("/{role}")
    public ApiResponse<Optional<FirstTripDtos.Celebration>> pending(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                     @PathVariable FirstTripExperience.Role role) {
        return ApiResponse.ok(firstTripService.pending(principal.getUserId(), role));
    }

    @PostMapping("/{role}/share")
    public ApiResponse<Void> share(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable FirstTripExperience.Role role,
                                   @Valid @RequestBody FirstTripDtos.ShareRequest request) {
        firstTripService.share(principal.getUserId(), role, request);
        return ApiResponse.ok("Thanks for sharing", null);
    }

    @PostMapping("/{role}/skip")
    public ApiResponse<Void> skip(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable FirstTripExperience.Role role) {
        firstTripService.skip(principal.getUserId(), role);
        return ApiResponse.ok("Skipped", null);
    }
}
