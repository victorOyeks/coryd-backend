package org.coryd.trip.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.trip.dto.PilotMetricsResponse;
import org.coryd.trip.service.PilotMetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only (ops-service) - the pilot numbers for the Ops Console. */
@Hidden
@RestController
@RequestMapping("/internal/ops")
@RequiredArgsConstructor
public class InternalOpsPilotController {

    private final PilotMetricsService pilotMetricsService;

    @GetMapping("/pilot-metrics")
    public ApiResponse<PilotMetricsResponse> metrics(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(pilotMetricsService.metrics(Math.max(1, Math.min(days, 90))));
    }
}
