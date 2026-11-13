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
import java.util.Map;

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

    /** A rider's paid trips, newest first - search by route, driver, date ("11 July") or day ("wed"). */
    public RiderTripPage riderTrips(String riderId, String search, int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, RiderTripPage.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/ops/riders/{riderId}/trips").queryParam("page", page).queryParam("size", size);
                    if (search != null && !search.isBlank()) {
                        b.queryParam("search", search);
                    }
                    return b.build(riderId);
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public RiderReceipt riderReceipt(String riderId, String bookingId) {
        return DownstreamCalls.data(SERVICE, objectMapper, RiderReceipt.class, () -> restClient.get()
                .uri("/internal/ops/riders/{riderId}/trips/{bookingId}/receipt", riderId, bookingId)
                .retrieve()
                .body(JsonNode.class));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiderTripPage(List<RiderTrip> content, int page, int size, long totalElements, int totalPages, boolean last) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiderTrip(String bookingId, String routePostId, BookingStatus status, String originLabel, String destinationLabel,
                            Instant departureTime, int seatCount, BigDecimal fareTotal, String driverId, String driverName,
                            Double driverRatingAvg, Long driverRatingCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiderReceipt(String bookingId, String riderId, String routePostId, BookingStatus status, String originLabel,
                               String destinationLabel, String pickupLabel, String dropoffLabel, Instant departureTime,
                               Instant bookedAt, Instant paidAt, Instant pickedUpAt, Instant completedAt, String driverId,
                               String driverName, String vehiclePlate, int seatCount, BigDecimal farePerSeat, BigDecimal fareTotal) {
    }

    /** The Trips table's filters, forwarded as trip-service takes them - every field optional. */
    public record TripQuery(OpsTripStatus status, List<RouteStatus> routeStatuses, java.time.LocalDate from, java.time.LocalDate to,
                            String driverId, String riderId, String search, org.coryd.ops.dto.TripSort sort,
                            org.springframework.data.domain.Sort.Direction direction) {
    }

    public TripPage trips(TripQuery query, int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, TripPage.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/ops/trips").queryParam("page", page).queryParam("size", size);
                    filters(b, query);
                    if (query.status() != null) {
                        b.queryParam("status", query.status());
                    }
                    if (query.routeStatuses() != null && !query.routeStatuses().isEmpty()) {
                        b.queryParam("routeStatus", query.routeStatuses().toArray());
                    }
                    if (query.sort() != null) {
                        b.queryParam("sort", query.sort());
                    }
                    if (query.direction() != null) {
                        b.queryParam("direction", query.direction());
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    /** Badges follow every filter but the status. */
    public TripCounts counts(TripQuery query) {
        return DownstreamCalls.data(SERVICE, objectMapper, TripCounts.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/ops/trips/counts");
                    filters(b, query);
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    private static void filters(org.springframework.web.util.UriBuilder b, TripQuery query) {
        if (query.from() != null) {
            b.queryParam("from", query.from());
        }
        if (query.to() != null) {
            b.queryParam("to", query.to());
        }
        param(b, "driverId", query.driverId());
        param(b, "riderId", query.riderId());
        param(b, "search", query.search());
    }

    private static void param(org.springframework.web.util.UriBuilder b, String name, String value) {
        if (value != null && !value.isBlank()) {
            b.queryParam(name, value.trim());
        }
    }

    public StuckPage stuck(int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, StuckPage.class, () -> restClient.get()
                .uri(b -> b.path("/internal/ops/trips/stuck").queryParam("page", page).queryParam("size", size).build())
                .retrieve()
                .body(JsonNode.class));
    }

    /** Ops actions - trip-service's own refusal (400 with its message) comes back as is. */
    public JsonNode cancelTrip(String routePostId, Map<String, Object> body) {
        return post("/internal/ops/trips/{id}/cancel", routePostId, body);
    }

    public JsonNode resolveTrip(String routePostId, Map<String, Object> body) {
        return post("/internal/ops/trips/{id}/resolve", routePostId, body);
    }

    public JsonNode cancelBooking(String bookingId, Map<String, Object> body) {
        return post("/internal/ops/bookings/{id}/cancel", bookingId, body);
    }

    private JsonNode post(String path, String id, Map<String, Object> body) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.post()
                .uri(path, id)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }

    /** The Cancellations Log's filters, forwarded as trip-service takes them. */
    public record CancellationQuery(org.coryd.ops.dto.CancellationSource source, org.coryd.ops.dto.CancellationFault fault,
                                    org.coryd.ops.dto.CancellationKind kind, java.time.LocalDate from, java.time.LocalDate to,
                                    String driverId, String riderId, String routePostId, String search) {
    }

    public CancellationPage cancellations(CancellationQuery query, int page, int size) {
        return DownstreamCalls.data(SERVICE, objectMapper, CancellationPage.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/ops/cancellations").queryParam("page", page).queryParam("size", size);
                    cancellationFilters(b, query);
                    if (query.source() != null) {
                        b.queryParam("source", query.source());
                    }
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    public CancellationCounts cancellationCounts(CancellationQuery query) {
        return DownstreamCalls.data(SERVICE, objectMapper, CancellationCounts.class, () -> restClient.get()
                .uri(b -> {
                    b.path("/internal/ops/cancellations/counts");
                    cancellationFilters(b, query);
                    return b.build();
                })
                .retrieve()
                .body(JsonNode.class));
    }

    private static void cancellationFilters(org.springframework.web.util.UriBuilder b, CancellationQuery query) {
        if (query.fault() != null) {
            b.queryParam("fault", query.fault());
        }
        if (query.kind() != null) {
            b.queryParam("kind", query.kind());
        }
        if (query.from() != null) {
            b.queryParam("from", query.from());
        }
        if (query.to() != null) {
            b.queryParam("to", query.to());
        }
        param(b, "driverId", query.driverId());
        param(b, "riderId", query.riderId());
        param(b, "routePostId", query.routePostId());
        param(b, "search", query.search());
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

    /** Drivers who keep moving booked trips at the last minute - passed through as trip-service shapes it. */
    public JsonNode lastMinuteTimeChanges(int days, int minLastMinute) {
        return DownstreamCalls.data(SERVICE, objectMapper, () -> restClient.get()
                .uri(b -> b.path("/internal/ops/trips/time-changes/last-minute")
                        .queryParam("days", days).queryParam("minLastMinute", minLastMinute).build())
                .retrieve().body(JsonNode.class));
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
    public record TripCounts(long all, long active, long completed, long cancelled, long published, long full, long inProgress,
                             long stuck) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StuckTrip(TripRow trip, Instant expectedArrivalAt, Instant stuckSince, long minutesPastExpectedArrival,
                            int boarded, int awaitingPickup, int completed, Location lastSeen, Long locationAgeSeconds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StuckPage(List<StuckTrip> content, int page, int size, long totalElements, int totalPages, boolean last) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cancellation(String id, org.coryd.ops.dto.CancellationKind kind, String routePostId, String bookingId, String parentId,
                               String riderId, String driverId, org.coryd.ops.dto.CancellationSource source, String cancelledBy,
                               String actorId, String actorName, String reason, org.coryd.ops.dto.CancellationFault fault,
                               Instant cancelledAt, Instant departureTime, int minutesBeforeDeparture, String routeStatusBefore,
                               String bookingStatusBefore, Integer seatCount, BigDecimal fareTotal, boolean paid,
                               BigDecimal feeAmount, BigDecimal refundExpected, int bookingsAffected, String originLabel,
                               String destinationLabel, boolean backfilled, List<String> childBookingIds) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CancellationPage(List<Cancellation> content, int page, int size, long totalElements, int totalPages, boolean last) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CancellationCounts(long all, long rider, long driver, long systemExpiry, long system, long ops, long suspension,
                                     long accountDeleted) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Timeline(Instant at, String type, String actor, String actorId, String actorName, String bookingId, String riderId,
                           String detail) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(boolean available, Double lat, Double lng, Instant recordedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TripDetail(TripRow trip, Double originLat, Double originLng, Double destinationLat, Double destinationLng,
                             String routeNote, Integer estimatedDurationMinutes, Location location, List<Passenger> passengers,
                             Instant postedAt, Instant originalDepartureTime, int timeChangeCount, Instant expectedArrivalAt,
                             boolean stuck, Instant stuckSince, Location lastSeen, Long locationAgeSeconds,
                             Cancellation cancellation, Actions actions, List<Timeline> timeline) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Passenger(String bookingId, String riderId, Integer seatCount, BigDecimal fareTotal, BookingStatus status,
                                String pickupLabel, Double pickupLat, Double pickupLng, Instant confirmedAt, Instant pickedUpAt,
                                String dropoffLabel, Instant completedAt, boolean cancellable, BigDecimal riderFaultFeeNow,
                                Cancellation cancellation) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Actions(boolean cancel, boolean resolve) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LiveTrip(String routePostId, String driverId, String originLabel, String destinationLabel,
                           Double originLat, Double originLng, Double destinationLat, Double destinationLng,
                           Instant departureTime, Instant startedAt, LiveTripPhase phase, Integer seatCapacity, int seatsBooked,
                           int estimatedDurationMinutes, long elapsedMinutes, Instant etaAt, double progress,
                           Double driverRating, long driverRatingCount, Location location, Location lastSeen,
                           Long locationAgeSeconds, boolean locationStale, boolean overdue, boolean stuck) {
    }
}
