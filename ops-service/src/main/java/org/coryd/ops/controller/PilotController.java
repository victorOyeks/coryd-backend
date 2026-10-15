package org.coryd.ops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.ops.client.IdentityServiceClient;
import org.coryd.ops.client.TripServiceClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * Whether the pilot is working: search success, search to booking, seat fill, week-2 repeat riders,
 * notify-me bookings and the most-searched routes. trip-service works them out; this passes them through.
 */
@RestController
@RequiredArgsConstructor
public class PilotController {

    private final TripServiceClient tripServiceClient;
    private final IdentityServiceClient identityServiceClient;

    /** Sign-ups in the last `days` by "How did you hear about us?", plus the most common "Other" answers. */
    @GetMapping("/metrics/heard-about-us")
    public ApiResponse<JsonNode> heardAboutUs(@RequestParam(defaultValue = "30") int days) {
        Instant to = Instant.now();
        return ApiResponse.ok(identityServiceClient.heardAboutUs(to.minus(Duration.ofDays(Math.max(1, Math.min(days, 365)))), to));
    }

    @GetMapping("/metrics/pilot")
    public ApiResponse<JsonNode> pilotMetrics(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(tripServiceClient.pilotMetrics(days));
    }
}
