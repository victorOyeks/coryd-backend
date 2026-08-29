package org.coryd.payment.dev;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev-only. Registered only when coryd.dev-tools.enabled is true, and additionally gated by
 * InternalApiKeyFilter (InternalOnlyPaths.PAYMENT_SERVICE covers /internal/**) so a mobile JWT
 * alone cannot reach it - only trip-service, holding the internal key, can. Hidden from Swagger
 * along with every other /internal/** endpoint.
 */
@RestController
@RequestMapping("/internal/dev")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coryd.dev-tools.enabled", havingValue = "true")
public class PaymentDevController {

    private final PaymentDevService paymentDevService;

    @PostMapping("/bookings/purge")
    public ApiResponse<PurgeBookingsResult> purge(@Valid @RequestBody PurgeBookingsRequest request) {
        return ApiResponse.ok("Payment records purged", paymentDevService.purge(request.bookingIds()));
    }
}
