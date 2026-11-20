package org.coryd.trip.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.trip.dto.BookingSummary;
import org.coryd.trip.dto.DistanceMatrixResult;
import org.coryd.trip.dto.DriverActiveTripResponse;
import org.coryd.trip.dto.LocationResponse;
import org.coryd.trip.dto.NextStop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NextStopEtaServiceTest {

    private static final String ROUTE = "route-1";
    // Ikeja -> Victoria Island
    private static final double DEST_LAT = 6.4281, DEST_LNG = 3.4219;

    private final LocationService locationService = mock(LocationService.class);
    private final MapsService mapsService = mock(MapsService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final Map<String, String> redis = new HashMap<>();
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final NextStopEtaService service = new NextStopEtaService(locationService, mapsService, redisTemplate, objectMapper);

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(inv -> redis.get(inv.<String>getArgument(0)));
        doAnswer(inv -> redis.put(inv.getArgument(0), inv.getArgument(1)))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        driverAt(6.6018, 3.3515);
        when(mapsService.getDistanceMatrixBatch(anyDouble(), anyDouble(), anyList()))
                .thenReturn(List.of(new DistanceMatrixResult(3.4, 9)));
    }

    // ---- which stop ------------------------------------------------------------------------------

    @Test
    void firstRiderStillToCollectInStopOrderIsNext() {
        var trip = trip(List.of(
                booking("far", BookingStatus.CONFIRMED, 9000.0, 6.55, 3.36, null),
                booking("near", BookingStatus.EN_ROUTE_TO_PICKUP, 2000.0, 6.59, 3.35, null),
                booking("aboard", BookingStatus.IN_PROGRESS, 0.0, 6.60, 3.35, null)));

        var target = NextStopEtaService.chooseTarget(trip);

        assertThat(target.kind()).isEqualTo(NextStop.Kind.PICKUP);
        assertThat(target.bookingId()).isEqualTo("near");
    }

    @Test
    void everyoneAboardMeansTheFirstEarlyDropOff() {
        var trip = trip(List.of(
                booking("toTheEnd", BookingStatus.IN_PROGRESS, 0.0, 6.60, 3.35, new double[]{DEST_LAT, DEST_LNG, 20000}),
                booking("later", BookingStatus.IN_PROGRESS, 0.0, 6.60, 3.35, new double[]{6.45, 3.40, 15000}),
                booking("sooner", BookingStatus.IN_PROGRESS, 100.0, 6.60, 3.35, new double[]{6.50, 3.37, 8000})));

        var target = NextStopEtaService.chooseTarget(trip);

        assertThat(target.kind()).isEqualTo(NextStop.Kind.DROPOFF);
        assertThat(target.bookingId()).isEqualTo("sooner");
    }

    @Test
    void nobodyLeftToCollectOrDropMeansTheDestination() {
        var trip = trip(List.of(
                booking("toTheEnd", BookingStatus.IN_PROGRESS, 0.0, 6.60, 3.35, new double[]{DEST_LAT, DEST_LNG, 20000})));

        var target = NextStopEtaService.chooseTarget(trip);

        assertThat(target.kind()).isEqualTo(NextStop.Kind.DESTINATION);
        assertThat(target.bookingId()).isNull();
        assertThat(target.label()).isEqualTo("Victoria Island");
    }

    // ---- ETA and caching -------------------------------------------------------------------------

    @Test
    void etaComesFromGoogleForTheLivePoint() {
        NextStop stop = service.forTrip(oneWaitingRider());

        assertThat(stop.kind()).isEqualTo(NextStop.Kind.PICKUP);
        assertThat(stop.bookingId()).isEqualTo("b1");
        assertThat(stop.etaMinutes()).isEqualTo(9);
        assertThat(stop.distanceMeters()).isEqualTo(3400);
        assertThat(stop.estimated()).isFalse();
    }

    @Test
    void pollsWithinAMinuteReuseTheAnswer() {
        service.forTrip(oneWaitingRider());
        driverAt(6.6010, 3.3515); // ~90 m on
        NextStop again = service.forTrip(oneWaitingRider());

        assertThat(again.etaMinutes()).isEqualTo(9);
        verify(mapsService, times(1)).getDistanceMatrixBatch(anyDouble(), anyDouble(), anyList());
    }

    @Test
    void movingAFewHundredMetresAsksAgain() {
        service.forTrip(oneWaitingRider());
        driverAt(6.5950, 3.3515); // ~750 m on
        service.forTrip(oneWaitingRider());

        verify(mapsService, times(2)).getDistanceMatrixBatch(anyDouble(), anyDouble(), anyList());
    }

    @Test
    void aMinuteOldAnswerIsWorkedOutAgain() throws Exception {
        NextStop first = service.forTrip(oneWaitingRider());
        var old = new NextStopEtaService.Cached(6.6018, 3.3515, new NextStop(first.kind(), first.bookingId(), first.label(),
                first.lat(), first.lng(), 12, 4000, false, Instant.now().minus(61, ChronoUnit.SECONDS)));
        redis.put(NextStopEtaService.CACHE_PREFIX + ROUTE, objectMapper.writeValueAsString(old));

        NextStop again = service.forTrip(oneWaitingRider());

        assertThat(again.etaMinutes()).isEqualTo(9);
        verify(mapsService, times(2)).getDistanceMatrixBatch(anyDouble(), anyDouble(), anyList());
    }

    @Test
    void aDifferentNextStopIsWorkedOutAtOnce() {
        service.forTrip(oneWaitingRider());
        service.forTrip(trip(List.of(booking("b1", BookingStatus.IN_PROGRESS, 500.0, 6.59, 3.35, null))));

        verify(mapsService, times(2)).getDistanceMatrixBatch(anyDouble(), anyDouble(), anyList());
    }

    @Test
    void googleDownFallsBackToAStraightLineAtCitySpeed() {
        when(mapsService.getDistanceMatrixBatch(anyDouble(), anyDouble(), anyList()))
                .thenThrow(new BadRequestException("Maps service is temporarily unavailable."));

        NextStop stop = service.forTrip(oneWaitingRider());

        // 6.6018,3.3515 -> 6.59,3.35 is ~1.32 km straight, ~1.72 km by road at 20 km/h = 6 min
        assertThat(stop.estimated()).isTrue();
        assertThat(stop.distanceMeters()).isBetween(1650, 1800);
        assertThat(stop.etaMinutes()).isEqualTo(6);
    }

    @Test
    void noLivePointMeansNoEta() {
        when(locationService.peekRouteLocation(ROUTE)).thenReturn(LocationResponse.unavailable("Waiting"));

        assertThat(service.forTrip(oneWaitingRider())).isNull();
        verify(mapsService, never()).getDistanceMatrixBatch(anyDouble(), anyDouble(), anyList());
    }

    @Test
    void beforeTheStartThereIsNoEta() {
        var trip = trip(List.of(booking("b1", BookingStatus.CONFIRMED, 500.0, 6.59, 3.35, null)), RouteStatus.PUBLISHED);

        assertThat(service.forTrip(trip)).isNull();
        verify(locationService, never()).peekRouteLocation(anyString());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private void driverAt(double lat, double lng) {
        when(locationService.peekRouteLocation(ROUTE)).thenReturn(new LocationResponse(true, lat, lng, Instant.now(), null));
    }

    private DriverActiveTripResponse oneWaitingRider() {
        return trip(List.of(booking("b1", BookingStatus.CONFIRMED, 500.0, 6.59, 3.35, null)));
    }

    private static DriverActiveTripResponse trip(List<BookingSummary> bookings) {
        return trip(bookings, RouteStatus.IN_PROGRESS);
    }

    private static DriverActiveTripResponse trip(List<BookingSummary> bookings, RouteStatus status) {
        return new DriverActiveTripResponse(ROUTE, "v1", "Ikeja", 6.6018, 3.3515, "Victoria Island", DEST_LAT, DEST_LNG,
                Instant.now(), new BigDecimal("1500"), 4, 4 - bookings.size(), bookings.size(), status, bookings, 0, null);
    }

    /** dropoff: {lat, lng, alongMeters}, or null for "rides to the end" with no drop-off saved. */
    private static BookingSummary booking(String id, BookingStatus status, Double pickupAlong, double pLat, double pLng, double[] dropoff) {
        return new BookingSummary(id, "rider-" + id, "Rider " + id, null, 1, new BigDecimal("1500"), status, null,
                "Pickup " + id, pLat, pLng,
                dropoff == null ? null : "Dropoff " + id, dropoff == null ? null : dropoff[0], dropoff == null ? null : dropoff[1],
                null, null, false, pickupAlong, dropoff == null ? null : dropoff[2]);
    }
}
