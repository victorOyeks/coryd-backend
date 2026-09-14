package org.coryd.profile.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.profile.dto.ProfileStatsResponse;
import org.coryd.profile.service.PlatformStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only (notification-service's report jobs) - gated by InternalApiKeyFilter via the /internal/** prefix. */
@Hidden
@RestController
@RequestMapping("/internal/stats")
@RequiredArgsConstructor
public class InternalStatsController {

    private final PlatformStatsService platformStatsService;

    @GetMapping
    public ApiResponse<ProfileStatsResponse> stats() {
        return ApiResponse.ok(platformStatsService.stats());
    }
}
