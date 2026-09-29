package org.coryd.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.http.HttpTimeouts;
import org.coryd.common.security.JwtUtil;
import org.coryd.common.security.client.ForwardedAuthInterceptor;
import org.coryd.common.security.client.InternalApiKeyInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;

/** The booking a payment is for, as trip-service holds it. Unlike the profile lookups this never
 * degrades - a payment can't be checked against a booking it can't see, so it doesn't start. */
@Component
public class TripServiceClient {

    private final RestClient restClient;

    public TripServiceClient(@Value("${services.trip.url}") String tripServiceUrl,
                             @Value("${services.internal.api-key}") String internalApiKey,
                             JwtUtil jwtUtil) {
        this.restClient = HttpTimeouts.builder()
                .baseUrl(tripServiceUrl)
                .requestInterceptor(new ForwardedAuthInterceptor(jwtUtil))
                .requestInterceptor(new InternalApiKeyInterceptor(internalApiKey))
                .build();
    }

    public BookingPaymentView fetchPaymentView(String bookingId) {
        try {
            PaymentViewResponse response = restClient.get()
                    .uri("/internal/bookings/{bookingId}/payment-view", bookingId)
                    .retrieve()
                    .body(PaymentViewResponse.class);
            if (response == null || response.data() == null) {
                throw new IllegalStateException("trip-service returned no booking for " + bookingId);
            }
            return response.data();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ResourceNotFoundException("Booking not found");
            }
            throw e;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BookingPaymentView(String bookingId, String riderId, String driverId, BookingStatus status,
                                     BigDecimal fareTotal, Instant holdExpiresAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PaymentViewResponse(BookingPaymentView data) {
    }
}
