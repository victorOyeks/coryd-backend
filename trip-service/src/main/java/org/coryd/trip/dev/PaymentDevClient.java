package org.coryd.trip.dev;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.security.ForwardedAuthInterceptor;
import org.coryd.common.security.InternalApiKeyInterceptor;
import org.coryd.common.http.HttpTimeouts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Calls payment-service's dev purge so a reset route does not leave wallet balances and
 * earnings totals crediting trips that no longer exist. Dev-only on both ends - this bean
 * and the endpoint it targets each require coryd.dev-tools.enabled.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "coryd.dev-tools.enabled", havingValue = "true")
public class PaymentDevClient {

    private final RestClient restClient;

    public PaymentDevClient(@Value("${services.payment.url}") String paymentServiceUrl,
                            @Value("${services.internal.api-key}") String internalApiKey) {
        this.restClient = HttpTimeouts.builder()
                .baseUrl(paymentServiceUrl)
                .requestInterceptor(new ForwardedAuthInterceptor())
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }

    /**
     * Deliberately lets failures propagate: it runs inside the reset transaction, so a
     * payment-service error rolls the trip-side deletes back rather than leaving the two
     * services disagreeing about which bookings exist.
     */
    public String purgeBookings(List<String> bookingIds) {
        if (bookingIds.isEmpty()) {
            return "no bookings to purge";
        }
        PurgeView response = restClient.post()
                .uri("/internal/dev/bookings/purge")
                .body(Map.of("bookingIds", bookingIds))
                .retrieve()
                .body(PurgeView.class);
        String summary = response == null || response.data() == null
                ? "payment-service returned no detail"
                : response.data().toString();
        log.warn("DEV payment purge for {} booking(s): {}", bookingIds.size(), summary);
        return summary;
    }

    private record PurgeView(Object data) {
    }
}
