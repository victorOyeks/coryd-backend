package org.coryd.payment.controller.internal;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.payment.service.PayoutRecipientService;
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

    private final PayoutRecipientService payoutRecipientService;

    @GetMapping("/drivers/{driverId}/exists")
    public ApiResponse<Boolean> exists(@PathVariable String driverId) {
        return ApiResponse.ok(payoutRecipientService.hasPayoutAccount(driverId));
    }
}
