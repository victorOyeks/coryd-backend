package org.coryd.trip.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.trip.dto.SafetyCheckAnswer;
import org.coryd.trip.entity.TripSafetyAlert;
import org.coryd.trip.service.TripSafetyService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The answer to an "Are you OK?" push - its referenceId is the alertId. */
@RestController
@RequestMapping("/safety-checks")
@RequiredArgsConstructor
public class SafetyCheckController {

    private final TripSafetyService tripSafetyService;

    @PostMapping("/{alertId}/answer")
    public ApiResponse<TripSafetyAlert.Status> answer(@AuthenticationPrincipal CoRydUserDetails principal, @PathVariable String alertId,
                                                      @Valid @RequestBody SafetyCheckAnswer answer) {
        return ApiResponse.ok(tripSafetyService.respond(alertId, principal.getUserId(), answer.ok(),
                answer.latitude(), answer.longitude()).getStatus());
    }
}
