package org.coryd.payment.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.payment.dto.InitiatePaymentRequest;
import org.coryd.payment.dto.InitiatePaymentResponse;
import org.coryd.payment.dto.PaymentSummaryResponse;
import org.coryd.payment.gateway.PaymentGatewayClient;
import org.coryd.payment.service.PaymentService;
import org.coryd.payment.service.PayoutService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private static final String EVENT_TRANSFER_SUCCESS = "transfer.success";
    private static final String EVENT_TRANSFER_FAILED = "transfer.failed";
    private static final String EVENT_TRANSFER_REVERSED = "transfer.reversed";

    private final PaymentService paymentService;
    private final PayoutService payoutService;
    private final PaymentGatewayClient gatewayClient;
    private final ObjectMapper objectMapper;

    @PostMapping("/initiate")
    public ApiResponse<InitiatePaymentResponse> initiate(@AuthenticationPrincipal CoRydUserDetails principal,
                                                          @Valid @RequestBody InitiatePaymentRequest request) {
        return ApiResponse.ok(paymentService.initiate(request, principal.getUserId()));
    }

    @GetMapping("/bookings/{bookingId}")
    public ApiResponse<PaymentSummaryResponse> byBooking(@PathVariable String bookingId) {
        return ApiResponse.ok(paymentService.getByBooking(bookingId));
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
            case EVENT_CHARGE_SUCCESS -> paymentService.handleWebhookSuccess(event.data().reference(),
                    event.data().bookingId(), event.data().amount());
            case EVENT_CHARGE_FAILED -> paymentService.handleWebhookFailure(event.data().reference(),
                    event.data().gatewayResponse() != null ? event.data().gatewayResponse() : "Charge failed");
            case EVENT_TRANSFER_SUCCESS -> payoutService.handleTransferSuccess(event.data().reference());
            case EVENT_TRANSFER_FAILED -> payoutService.handleTransferFailure(event.data().reference(),
                    event.data().gatewayResponse() != null ? event.data().gatewayResponse() : "Transfer failed");
            case EVENT_TRANSFER_REVERSED -> payoutService.handleTransferFailure(event.data().reference(),
                    event.data().gatewayResponse() != null ? event.data().gatewayResponse() : "Transfer reversed");
            default -> log.info("Ignoring unhandled Paystack webhook event: {}", event.event());
        }

        return ApiResponse.ok("Webhook accepted", null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WebhookPayload(String event, Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(String reference, @JsonProperty("gateway_response") String gatewayResponse,
                            Long amount, JsonNode metadata) {
            /** Paystack sends metadata as an object when we set it and as "" when we didn't - read it loosely. */
            String bookingId() {
                return metadata != null && metadata.hasNonNull("bookingId") ? metadata.get("bookingId").asText() : null;
            }
        }
    }
}
