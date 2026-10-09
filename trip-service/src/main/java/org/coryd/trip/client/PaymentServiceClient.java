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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PostingStatus(boolean hasPayoutAccount, BigDecimal amountOwed, boolean overOwedLimit) {
    }

    private record StatusView(PostingStatus data) {
    }
}
