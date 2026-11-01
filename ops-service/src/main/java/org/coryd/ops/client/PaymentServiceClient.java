package org.coryd.ops.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/** Payments and refunds live in payment-service, served to this service under /internal/ops/payments. */
@Component
public class PaymentServiceClient {

    private static final String SERVICE = "payment-service";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public PaymentServiceClient(@Value("${services.payment.url}") String paymentServiceUrl,
                                @Value("${services.internal.api-key}") String internalApiKey,
                                JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.restClient = OpsRestClients.build(paymentServiceUrl, internalApiKey, jwtUtil);
        this.objectMapper = objectMapper;
    }

    /** By booking id - empty if payment-service can't be reached, so the trips still show. */
    public Map<String, BookingPayment> bookingPayments(Collection<String> bookingIds) {
        return DownstreamCalls.batch(SERVICE, objectMapper, bookingIds, BookingPayment.class, BookingPayment::bookingId, ids -> restClient.get()
                .uri(b -> b.path("/internal/ops/payments/bookings").queryParam("ids", ids).build())
                .retrieve()
                .body(JsonNode.class));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BookingPayment(String bookingId, String status, String method, String cardType, String cardLast4, String reference,
                                 Instant paidAt, BigDecimal amountPaid, BigDecimal refunded, BigDecimal refundPending,
                                 BigDecimal netPaid) {
    }
}
