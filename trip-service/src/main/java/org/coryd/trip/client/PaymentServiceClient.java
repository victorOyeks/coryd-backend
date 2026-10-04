package org.coryd.trip.client;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.client.ForwardedAuthInterceptor;
import org.coryd.common.security.client.InternalApiKeyInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

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
    public Boolean hasPayoutAccount(String driverId) {
        try {
            ExistsView response = restClient.get()
                    .uri("/internal/payout-recipients/drivers/{driverId}/exists", driverId)
                    .retrieve()
                    .body(ExistsView.class);
            return response == null ? null : response.data();
        } catch (Exception e) {
            log.warn("Could not check payout account for driver {}: {}", driverId, e.getMessage());
            return null;
        }
    }

    private record ExistsView(Boolean data) {
    }
}
