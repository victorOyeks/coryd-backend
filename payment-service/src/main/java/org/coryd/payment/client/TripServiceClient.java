package org.coryd.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.extern.slf4j.Slf4j;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The booking a payment is for, as trip-service holds it. Unlike the profile lookups this never
 * degrades - a payment can't be checked against a booking it can't see, so it doesn't start. */
@Slf4j
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

    /** Route text for payment histories - a trip-service hiccup leaves the routes off, never fails the page. */
    public Map<String, BookingRoute> fetchBookingRoutes(List<String> bookingIds) {
        if (bookingIds.isEmpty()) {
            return Map.of();
        }
        try {
            BookingRoutesResponse response = restClient.get()
                    .uri(b -> b.path("/internal/bookings/route-summaries").queryParam("ids", bookingIds).build())
                    .retrieve()
                    .body(BookingRoutesResponse.class);
            if (response == null || response.data() == null) {
                return Map.of();
            }
            return response.data().stream().collect(Collectors.toMap(BookingRoute::bookingId, r -> r, (a, b) -> a));
        } catch (Exception e) {
            log.warn("Could not load routes for {} booking(s): {}", bookingIds.size(), e.getMessage());
            return Map.of();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BookingRoute(String bookingId, String routePostId, String originLabel, String destinationLabel, Instant departureTime) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BookingRoutesResponse(List<BookingRoute> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BookingPaymentView(String bookingId, String riderId, String driverId, BookingStatus status,
                                     BigDecimal fareTotal, Instant holdExpiresAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PaymentViewResponse(BookingPaymentView data) {
    }
}
