package org.coryd.payment.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.coryd.common.response.ApiResponse;
import org.coryd.payment.dto.InitiatePaymentRequest;
import org.coryd.payment.dto.InitiatePaymentResponse;
import org.coryd.payment.gateway.PaymentGatewayClient;
import org.coryd.payment.service.PaymentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private static final String PAYSTACK_SIGNATURE_HEADER = "x-paystack-signature";

    private final PaymentService paymentService;
    private final PaymentGatewayClient gatewayClient;

    @PostMapping("/initiate")
    public ApiResponse<InitiatePaymentResponse> initiate(@Valid @RequestBody InitiatePaymentRequest request) {
        return ApiResponse.ok(paymentService.initiate(request));
    }

    @PostMapping("/webhook")
    public ApiResponse<Void> webhook(@RequestBody String payload,
                                      @RequestHeader(PAYSTACK_SIGNATURE_HEADER) String signature) {
        if (!gatewayClient.verifyWebhookSignature(payload, signature)) {
            return ApiResponse.error("Invalid webhook signature");
        }
        // Parsing the event type/reference out of the raw payload is gateway-specific
        // and intentionally left as a follow-up once the vendor is finalized (Section 15).
        return ApiResponse.ok("Webhook accepted", null);
    }
}
