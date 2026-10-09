package org.coryd.payment.controller;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.response.ApiResponse;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.security.CoRydUserDetails;
import org.coryd.payment.dto.CardAuthorization;
import org.coryd.payment.dto.InitiatePaymentRequest;
import org.coryd.payment.dto.InitiatePaymentResponse;
import org.coryd.payment.dto.PaymentSummaryResponse;
import org.coryd.payment.dto.RiderPaymentItem;
import org.coryd.payment.gateway.PaymentGatewayClient;
import org.coryd.payment.service.PaymentHistoryService;
import org.coryd.payment.service.PaymentService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final PaymentHistoryService paymentHistoryService;
    private final PaymentGatewayClient gatewayClient;
    private final ObjectMapper objectMapper;

    @PostMapping("/initiate")
    public ApiResponse<InitiatePaymentResponse> initiate(@AuthenticationPrincipal CoRydUserDetails principal,
                                                          @Valid @RequestBody InitiatePaymentRequest request) {
        return ApiResponse.ok(paymentService.initiate(request, principal.getUserId()));
    }

    /** A rider's trip payments and refunds, newest first. */
    @GetMapping("/riders/{riderId}/history")
    public ApiResponse<PagedResponse<RiderPaymentItem>> riderHistory(@AuthenticationPrincipal CoRydUserDetails principal,
                                                                     @PathVariable String riderId,
                                                                     @RequestParam(defaultValue = "0") int page,
                                                                     @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        return ApiResponse.ok(paymentHistoryService.riderHistory(riderId, pageable, principal.getUserId(), principal.getActiveRole()));
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
                    event.data().bookingId(), event.data().amount(), event.data().card());
            case EVENT_CHARGE_FAILED -> paymentService.handleWebhookFailure(event.data().reference(),
                    event.data().gatewayResponse() != null ? event.data().gatewayResponse() : "Charge failed");
            default -> log.info("Ignoring unhandled Paystack webhook event: {}", event.event());
        }

        return ApiResponse.ok("Webhook accepted", null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WebhookPayload(String event, Data data) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Data(String reference, @JsonProperty("gateway_response") String gatewayResponse,
                            Long amount, JsonNode metadata, Authorization authorization, Customer customer) {
            /** Paystack sends metadata as an object when we set it and as "" when we didn't - read it loosely. */
            String bookingId() {
                return metadata != null && metadata.hasNonNull("bookingId") ? metadata.get("bookingId").asText() : null;
            }

            CardAuthorization card() {
                if (authorization == null) {
                    return null;
                }
                return new CardAuthorization(authorization.authorizationCode(), authorization.signature(), authorization.channel(),
                        authorization.reusable(), authorization.cardType(), authorization.last4(), authorization.expMonth(),
                        authorization.expYear(), authorization.bank(), customer == null ? null : customer.email());
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Authorization(@JsonProperty("authorization_code") String authorizationCode, String signature,
                                     String channel, boolean reusable, @JsonProperty("card_type") String cardType,
                                     String last4, @JsonProperty("exp_month") String expMonth,
                                     @JsonProperty("exp_year") String expYear, String bank) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Customer(String email) {
        }
    }
}
