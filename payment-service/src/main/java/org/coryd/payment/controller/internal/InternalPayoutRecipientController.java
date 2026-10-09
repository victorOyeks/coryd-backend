package org.coryd.payment.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.payment.dto.DriverPostingStatus;
import org.coryd.payment.service.EarningsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service only (trip-service's posting gate) - gated by InternalApiKeyFilter via the /internal/** prefix. */
@Hidden
@RestController
@RequestMapping("/internal/payout-recipients")
@RequiredArgsConstructor
public class InternalPayoutRecipientController {

    private final EarningsService earningsService;

    @GetMapping("/drivers/{driverId}/posting-status")
    public ApiResponse<DriverPostingStatus> postingStatus(@PathVariable String driverId) {
        return ApiResponse.ok(earningsService.postingStatus(driverId));
    }
}
