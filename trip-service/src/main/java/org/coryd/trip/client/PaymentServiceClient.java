package org.coryd.trip.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.client.ForwardedAuthInterceptor;
import org.coryd.common.security.client.InternalApiKeyInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PaymentServiceClient {

    private final RestClient restClient;

    public PaymentServiceClient(@Value("${services.payment.url}") String paymentServiceUrl,
                                @Value("${services.internal.api-key}") String internalApiKey,
                                JwtUtil jwtUtil) {
        this.restClient = HttpTimeouts.builder()
                .baseUrl(paymentServiceUrl)
                .requestInterceptor(new ForwardedAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }

    /** Null when payment-service can't be reached - callers decide what an unknown means. */
    public PostingStatus postingStatus(String driverId) {
        try {
            StatusView response = restClient.get()
                    .uri("/internal/payout-recipients/drivers/{driverId}/posting-status", driverId)
                    .retrieve()
                    .body(StatusView.class);
            return response == null ? null : response.data();
        } catch (Exception e) {
            log.warn("Could not check payment standing for driver {}: {}", driverId, e.getMessage());
            return null;
        }
    }

    /** The card fee the rider paid on top of this booking's fare (M10) - null when payment-service can't say. */
    public BigDecimal cardFee(String bookingId) {
        try {
            PaymentView response = restClient.get()
                    .uri("/payments/bookings/{bookingId}", bookingId)
                    .retrieve()
                    .body(PaymentView.class);
            return response == null || response.data() == null ? null : response.data().cardFee();
        } catch (Exception e) {
            log.warn("Could not read the card fee for booking {}: {}", bookingId, e.getMessage());
            return null;
        }
    }

    /**
     * What payment-service will send back to the card for each of these cancellations - the fare left after any earlier
     * ops refund, less the fee, plus the card fee when it goes back. Empty when payment-service can't be reached: callers
     * fall back to their own figure and say it's an estimate.
     */
    public Map<String, BigDecimal> cancellationRefunds(List<CancellationRefundQuery> queries) {
        if (queries.isEmpty()) {
            return Map.of();
        }
        try {
            CancellationRefundsView response = restClient.post()
                    .uri("/internal/ops/payments/cancellation-refunds")
                    .body(queries)
                    .retrieve()
                    .body(CancellationRefundsView.class);
            if (response == null || response.data() == null) {
                return Map.of();
            }
            return response.data().stream().filter(r -> r.refund() != null)
                    .collect(Collectors.toMap(CancellationRefund::bookingId, CancellationRefund::refund, (a, b) -> a));
        } catch (Exception e) {
            log.warn("Could not ask payment-service what {} cancellation(s) will refund: {}", queries.size(), e.getMessage());
            return Map.of();
        }
    }

    public record CancellationRefundQuery(String bookingId, BigDecimal fee, boolean refundCardFee) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CancellationRefund(String bookingId, BigDecimal refund) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CancellationRefundsView(List<CancellationRefund> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentSummary(BigDecimal amount, BigDecimal cardFee, BigDecimal amountCharged) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PaymentView(PaymentSummary data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PostingStatus(boolean hasPayoutAccount, BigDecimal amountOwed, boolean overOwedLimit) {
    }

    private record StatusView(PostingStatus data) {
    }
}
