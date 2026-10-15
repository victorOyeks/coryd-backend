package org.coryd.ops.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.LiveTripPhase;
import org.coryd.common.enums.OpsTripStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.security.JwtUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** Trip data lives in trip-service, served to this service alone under /internal/ops/trips. */
@Component
public class TripServiceClient {

    private static final String SERVICE = "trip-service";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public TripServiceClient(@Value("${services.trip.url}") String tripServiceUrl,
                             @Value("${services.internal.api-key}") String internalApiKey,
                             JwtUtil jwtUtil, ObjectMapper objectMapper) {
        this.restClient = OpsRestClients.build(tripServiceUrl, internalApiKey, jwtUtil);
        this.objectMapper = objectMapper;
    }

    public TripPage trips(OpsTripStatus status, String search, int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, TripPage.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/ops/trips").queryParam("page", page).queryParam("size", size);
                    if (status != null) {
                        b.queryParam("status", status);
                    }
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public TripCounts counts(String search) {
        return DownstreamCalls.data(SERVICE, objectMapper, TripCounts.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/ops/trips/counts");
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public TripDetail trip(String routePostId) {
        return DownstreamCalls.data(SERVICE, objectMapper, TripDetail.class, () -> restClient.get()
                .uri("/internal/ops/trips/{routePostId}", routePostId)
                .retrieve()
                .body(JsonNode.class));
    }

    public List<LiveTrip> live(String search) {
        LiveTrip[] trips = DownstreamCalls.data(SERVICE, objectMapper, LiveTrip[].class, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/ops/trips/live");
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
        return trips == null ? List.of() : Arrays.asList(trips);
    }

    public SosPage sos(int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, SosPage.class, () -> restClient.get()
                .uri(b -> b.path("/internal/ops/sos").queryParam("page", page).queryParam("size", size).build())
                .retrieve()
                .body(JsonNode.class));
    }

    public JsonNode pilotMetrics(int days) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri(b -> b.path("/internal/ops/pilot-metrics").queryParam("days", days).build())
                .retrieve().body(JsonNode.class));
    }

    /** Passed through as trip-service shapes it - the alert, its kind and status, and each answer. */
    public JsonNode safetyAlerts(int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri(b -> b.path("/internal/ops/sos/safety-alerts").queryParam("page", page).queryParam("size", size).build())
                .retrieve()
                .body(JsonNode.class));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SosRow(String id, Instant triggeredAt, String triggeredBy, String triggeredByRole, String bookingId,
                         String routePostId, String driverId, String originLabel, String destinationLabel, RouteStatus routeStatus,
                         Double latitude, Double longitude, Instant opsNotifiedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SosPage(List<SosRow> content, int page, int size, long totalElements, int totalPages, boolean last) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TripRow(String routePostId, String driverId, String vehicleId, String originLabel, String destinationLabel,
                          Instant departureTime, RouteStatus status, Integer seatCapacity, Integer seatsAvailable, int seatsBooked,
                          BigDecimal farePerSeat, BigDecimal totalFare, Double driverRating, long driverRatingCount,
                          Instant startedAt, Instant completedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TripPage(List<TripRow> content, int page, int size, long totalElements, int totalPages, boolean last) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TripCounts(long all, long active, long completed, long cancelled) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(boolean available, Double lat, Double lng, Instant recordedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TripDetail(TripRow trip, Double originLat, Double originLng, Double destinationLat, Double destinationLng,
                             String routeNote, Integer estimatedDurationMinutes, Location location, List<Passenger> passengers) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Passenger(String bookingId, String riderId, Integer seatCount, BigDecimal fareTotal, BookingStatus status,
                                String pickupLabel, Double pickupLat, Double pickupLng, Instant confirmedAt, Instant pickedUpAt) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LiveTrip(String routePostId, String driverId, String originLabel, String destinationLabel,
                           Double originLat, Double originLng, Double destinationLat, Double destinationLng,
                           Instant departureTime, Instant startedAt, LiveTripPhase phase, Integer seatCapacity, int seatsBooked,
                           int estimatedDurationMinutes, long elapsedMinutes, Instant etaAt, double progress,
                           Double driverRating, long driverRatingCount, Location location) {
    }
}
