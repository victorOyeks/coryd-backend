package org.coryd.payment.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class PaymentController {

    private static final String PAYSTACK_SIGNATURE_HEADER = "x-paystack-signature";
    private static final String EVENT_CHARGE_SUCCESS = "charge.success";
    private static final String EVENT_CHARGE_FAILED = "charge.failed";

    private final PaymentService paymentService;
    private final PaymentGatewayClient gatewayClient;
    private final ObjectMapper objectMapper;

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

        WebhookPayload event;
        try {
            event = objectMapper.readValue(payload, WebhookPayload.class);
        } catch (Exception e) {
            log.error("Failed to parse Paystack webhook payload: {}", e.getMessage());
            return ApiResponse.error("Malformed webhook payload");
        }

        if (event.data() == null || event.data().reference() == null) {
            log.warn("Paystack webhook missing data.reference for event {}", event.event());
            return ApiResponse.ok("Webhook accepted", null);
        }

        switch (event.event()) {
            case EVENT_CHARGE_SUCCESS -> paymentService.handleWebhookSuccess(event.data().reference());
            case EVENT_CHARGE_FAILED -> paymentService.handleWebhookFailure(event.data().reference(),
                    event.data().gatewayResponse() != null ? event.data().gatewayResponse() : "Charge failed");
            default -> log.info("Ignoring unhandled Paystack webhook event: {}", event.event());
        }

        return ApiResponse.ok("Webhook accepted", null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WebhookPayload(String event, Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(String reference, @JsonProperty("gateway_response") String gatewayResponse) {
        }
    }
}
