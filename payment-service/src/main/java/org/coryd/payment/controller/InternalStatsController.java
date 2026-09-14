package org.coryd.payment.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.payment.dto.PaymentStatsResponse;
import org.coryd.payment.service.PlatformStatsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Service-to-service only (notification-service's report jobs) - gated by InternalApiKeyFilter via the /internal/** prefix. */
@Hidden
@RestController
@RequestMapping("/internal/stats")
@RequiredArgsConstructor
public class InternalStatsController {

    private final PlatformStatsService platformStatsService;

    @GetMapping
    public ApiResponse<PaymentStatsResponse> stats(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ApiResponse.ok(platformStatsService.statsFor(from, to));
    }
}
