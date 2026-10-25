package org.coryd.identity.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.coryd.common.exception.ExternalServiceException;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.client.InternalApiKeyInterceptor;
import org.coryd.common.security.client.SystemAuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Asks trip-service and payment-service whether an account can be deleted right now - someone on a trip,
 * or with money still moving, can't be. If either service can't be asked, the answer is "not now":
 * deleting blind could strand a trip or a payment.
 */
@Component
public class DeletionBlockerClient {

    private final RestClient trip;
    private final RestClient payment;

    public DeletionBlockerClient(@Value("${services.trip.url}") String tripUrl,
                                 @Value("${services.payment.url}") String paymentUrl,
                                 @Value("${services.internal.api-key}") String internalApiKey,
                                 JwtUtil jwtUtil) {
        this.trip = build(tripUrl, internalApiKey, jwtUtil);
        this.payment = build(paymentUrl, internalApiKey, jwtUtil);
    }

    private static RestClient build(String baseUrl, String internalApiKey, JwtUtil jwtUtil) {
        return HttpTimeouts.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(new SystemAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }

    /** Every reason, in words the person can act on - empty when nothing stands in the way. */
    public List<String> blockers(String userId) {
        List<String> reasons = new ArrayList<>(ask(trip, "trip", userId));
        reasons.addAll(ask(payment, "payment", userId));
        return reasons;
    }

    private static List<String> ask(RestClient client, String name, String userId) {
        try {
            BlockersView view = client.get().uri("/internal/users/{userId}/deletion-blockers", userId).retrieve().body(BlockersView.class);
            return view == null || view.data() == null ? List.of() : view.data();
        } catch (Exception e) {
            throw new ExternalServiceException("We couldn't check your " + name + " records just now - please try again in a few minutes", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BlockersView(List<String> data) {
    }
}
