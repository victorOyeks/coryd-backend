package org.coryd.trip.service;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.common.kafka.events.RouteTimeChangedEvent;
import org.coryd.trip.client.IdentityServiceClient;
import org.coryd.trip.client.PaymentServiceClient;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.config.TimeChangeProperties;
import org.coryd.trip.config.policy.CancellationPolicy;
import org.coryd.trip.config.policy.DriverVerificationPolicy;
import org.coryd.trip.dto.BookSeatsRequest;
import org.coryd.trip.dto.ChangeTripTimeRequest;
import org.coryd.trip.dto.RouteTimeChangeResponse;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.RouteTimeChangeRepository;
import org.coryd.trip.scheduler.RouteDepartureReminderJob;
import org.coryd.trip.scheduler.RouteExpiryJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A driver running late changes their trip's time instead of cancelling it - against a throwaway Postgres
 * container built from trip-service's own migrations.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@org.springframework.test.context.TestPropertySource(properties = "identity.verification.enforcement=OFF")
@Import({BookingService.class, RouteService.class, UserBlockService.class, RouteMatcher.class, org.coryd.trip.config.GeoCapabilities.class,
        RouteTemplateService.class, org.coryd.trip.config.policy.IdentityVerificationPolicy.class, TripScheduleService.class,
        OpsTripQueryService.class, CancellationPolicy.class, MatchingProperties.class, NotifyMeService.class, DemandService.class,
        org.coryd.trip.config.DemandProperties.class, TimeChangeProperties.class, RouteTimeChangeService.class,
        RouteDepartureReminderJob.class, RouteExpiryJob.class, org.coryd.trip.config.SavingsProperties.class, NearbyDemandService.class, OpsDriverStatsService.class, DriverStatsPublisher.class,
        OpsRiderStatsService.class, RiderStatsPublisher.class, OpsRiderTripService.class})
class RouteTimeChangeIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    private static final String DRIVER = "driver-1";
    private static final String RIDER = "rider-1";
    private static final String OTHER_RIDER = "rider-2";
    private static final BigDecimal FARE = new BigDecimal("1200.00");

    @Autowired RouteTimeChangeService routeTimeChangeService;
    @Autowired BookingService bookingService;
    @Autowired RouteService routeService;
    @Autowired BookingRepository bookingRepository;
    @Autowired RoutePostRepository routePostRepository;
    @Autowired RouteTimeChangeRepository routeTimeChangeRepository;
    @Autowired RouteDepartureReminderJob reminderJob;
    @Autowired RouteExpiryJob expiryJob;
    @Autowired DataSource dataSource;

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;
    @MockBean ProfileServiceClient profileServiceClient;
    @MockBean PaymentServiceClient paymentServiceClient;
    @MockBean IdentityServiceClient identityServiceClient;
    @MockBean ShareTokenService shareTokenService;
    @MockBean LocationService locationService;
    @MockBean MapsService mapsService;
    @MockBean ChatService chatService;
    @MockBean LiveSearchSessionRegistry liveSearchSessionRegistry;
    @MockBean DriverVerificationPolicy driverVerificationPolicy;

    @BeforeEach
    void cleanSlate() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
        }
        when(paymentServiceClient.postingStatus(anyString()))
                .thenReturn(new PaymentServiceClient.PostingStatus(true, BigDecimal.ZERO, false));
        routeTimeChangeRepository.deleteAll();
        bookingRepository.deleteAll();
        routePostRepository.deleteAll();
        clearInvocations(kafkaTemplate);
    }

    // ---- nobody booked: a plain edit ------------------------------------------------------------

    @Test
    void withNobodyBookedTheTimeMovesEarlierOrLaterFreely() {
        RoutePost route = routeDepartingIn(120);
        Instant original = route.getDepartureTime();

        RouteTimeChangeResponse earlier = routeTimeChangeService.change(route.getId(), DRIVER, at(original.minus(60, ChronoUnit.MINUTES)));
        assertThat(earlier.departureTime()).isEqualTo(original.minus(60, ChronoUnit.MINUTES));
        assertThat(earlier.originalDepartureTime()).isEqualTo(original);
        assertThat(earlier.timeChangeCount()).as("only changes with riders booked count").isZero();
        assertThat(earlier.ridersTold()).isZero();

        routeTimeChangeService.change(route.getId(), DRIVER, plus(300));
        RoutePost moved = reload(route);
        assertThat(moved.getDepartureTime()).isEqualTo(original.plus(240, ChronoUnit.MINUTES));
        assertThat(moved.getOriginalDepartureTime()).as("the first time is kept").isEqualTo(original);
        assertThat(moved.getTimeChangeCount()).isZero();
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.ROUTE_TIME_CHANGED), any());
        assertThat(routeTimeChangeRepository.findByRoutePostIdOrderByCreatedAtAsc(route.getId())).hasSize(2)
                .allSatisfy(c -> {
                    assertThat(c.getRidersBooked()).isZero();
                    assertThat(c.isLastMinute()).isFalse();
                });
    }

    // ---- riders booked: later only, within limits, and told -------------------------------------

    @Test
    void withRidersBookedItMovesLaterKeepsTheirSeatsAndTellsEachPaidRider() {
        RoutePost route = routeDepartingIn(120);
        Booking paid = confirmed(route, RIDER);
        Booking unpaid = book(route, OTHER_RIDER);

        RouteTimeChangeResponse response = routeTimeChangeService.change(route.getId(), DRIVER, plus(30));

        assertThat(response.departureTime()).isEqualTo(route.getDepartureTime().plus(30, ChronoUnit.MINUTES));
        assertThat(response.timeChangeCount()).isEqualTo(1);
        assertThat(response.changesLeft()).isEqualTo(2);
        assertThat(response.ridersTold()).isEqualTo(1);
        assertThat(reload(paid).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(reload(paid).getDepartureShiftMinutes()).isEqualTo(30);
        assertThat(reload(unpaid).getDepartureShiftMinutes()).as("anyone holding a seat booked a time").isEqualTo(30);
        verify(kafkaTemplate).send(eq(KafkaTopics.ROUTE_TIME_CHANGED), argThat(e -> {
            RouteTimeChangedEvent event = (RouteTimeChangedEvent) e;
            return event.getRiders().size() == 1
                    && event.getRiders().get(0).getBookingId().equals(paid.getId())
                    && event.getRiders().get(0).isFreeCancellation()
                    && event.getPreviousDepartureTime().equals(route.getDepartureTime());
        }));
    }

    @Test
    void withRidersBookedItCannotMoveEarlier() {
        RoutePost route = routeDepartingIn(120);
        confirmed(route, RIDER);

        assertThatThrownBy(() -> routeTimeChangeService.change(route.getId(), DRIVER, at(route.getDepartureTime().minus(10, ChronoUnit.MINUTES))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("only move later");
        assertThat(reload(route).getDepartureTime()).isEqualTo(route.getDepartureTime());
    }

    @Test
    void withRidersBookedItMovesAtMostTwoHoursPastWhatTheyBookedInTotal() {
        RoutePost route = routeDepartingIn(120);
        confirmed(route, RIDER);

        routeTimeChangeService.change(route.getId(), DRIVER, plus(90));
        assertThatThrownBy(() -> routeTimeChangeService.change(route.getId(), DRIVER, plus(31)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("up to 30 more minutes");
        routeTimeChangeService.change(route.getId(), DRIVER, plus(30));
        assertThatThrownBy(() -> routeTimeChangeService.change(route.getId(), DRIVER, plus(1)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("it already has");
        assertThat(reload(route).getDepartureTime()).isEqualTo(route.getDepartureTime().plus(120, ChronoUnit.MINUTES));
    }

    @Test
    void aRiderWhoBookedAfterAChangeIsMeasuredFromTheTimeTheyBooked() {
        RoutePost route = routeDepartingIn(120);
        Booking early = confirmed(route, RIDER);
        routeTimeChangeService.change(route.getId(), DRIVER, plus(100));
        Booking late = confirmed(route, OTHER_RIDER);

        assertThat(reload(late).getDepartureShiftMinutes()).isZero();
        assertThatThrownBy(() -> routeTimeChangeService.change(route.getId(), DRIVER, plus(30)))
                .as("the earlier rider would be 130 minutes past what they booked")
                .isInstanceOf(BadRequestException.class);
        routeTimeChangeService.change(route.getId(), DRIVER, plus(20));
        assertThat(reload(early).getDepartureShiftMinutes()).isEqualTo(120);
        assertThat(reload(late).getDepartureShiftMinutes()).isEqualTo(20);
    }

    @Test
    void withRidersBookedTheTimeChangesAtMostThreeTimes() {
        RoutePost route = routeDepartingIn(120);
        confirmed(route, RIDER);

        for (int i = 0; i < 3; i++) {
            routeTimeChangeService.change(route.getId(), DRIVER, plus(10));
        }
        assertThatThrownBy(() -> routeTimeChangeService.change(route.getId(), DRIVER, plus(10)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cancel it and post again");
        assertThat(reload(route).getTimeChangeCount()).isEqualTo(3);
    }

    @Test
    void changesMadeBeforeAnyoneBookedDoNotUseUpTheLimit() {
        RoutePost route = routeDepartingIn(120);
        for (int i = 0; i < 4; i++) {
            routeTimeChangeService.change(route.getId(), DRIVER, plus(5));
        }
        confirmed(route, RIDER);

        RouteTimeChangeResponse response = routeTimeChangeService.change(route.getId(), DRIVER, plus(5));

        assertThat(response.timeChangeCount()).isEqualTo(1);
        assertThat(reload(onlyPaid(route)).getDepartureShiftMinutes()).isEqualTo(5);
    }

    // ---- the free cancellation ------------------------------------------------------------------

    @Test
    void aRiderMovedMoreThanFifteenMinutesCancelsFreeInsideTheFeeWindow() {
        RoutePost route = routeDepartingIn(30);
        Booking booking = confirmed(route, RIDER);
        routeTimeChangeService.change(route.getId(), DRIVER, plus(30));

        assertThat(bookingService.getBookingDetails(booking.getId(), RIDER).freeCancellation()).isTrue();
        bookingService.cancelByRider(booking.getId(), RIDER);

        verify(kafkaTemplate).send(eq(KafkaTopics.BOOKING_CANCELLED), argThat(e ->
                e instanceof BookingCancelledEvent c && c.getBookingId().equals(booking.getId()) && !c.isFeeApplied()));
    }

    @Test
    void aSmallDelayStillLeavesTheLateCancellationFeeInPlace() {
        RoutePost route = routeDepartingIn(30);
        Booking booking = confirmed(route, RIDER);
        routeTimeChangeService.change(route.getId(), DRIVER, plus(15));

        assertThat(bookingService.getBookingDetails(booking.getId(), RIDER).freeCancellation()).isFalse();
        bookingService.cancelByRider(booking.getId(), RIDER);

        verify(kafkaTemplate).send(eq(KafkaTopics.BOOKING_CANCELLED), argThat(e ->
                e instanceof BookingCancelledEvent c && c.getBookingId().equals(booking.getId()) && c.isFeeApplied()));
    }

    @Test
    void severalSmallDelaysAddUpToAFreeCancellation() {
        RoutePost route = routeDepartingIn(30);
        Booking booking = confirmed(route, RIDER);
        routeTimeChangeService.change(route.getId(), DRIVER, plus(10));
        routeTimeChangeService.change(route.getId(), DRIVER, plus(10));

        assertThat(bookingService.getBookingDetails(booking.getId(), RIDER).freeCancellation()).isTrue();
    }

    // ---- what a booking and a search show ------------------------------------------------------

    @Test
    void bookingDetailsShowTheNewTimeTheOriginalAndHowOftenItChanged() {
        RoutePost route = routeDepartingIn(120);
        Booking booking = confirmed(route, RIDER);
        routeTimeChangeService.change(route.getId(), DRIVER, plus(20));

        var details = bookingService.getBookingDetails(booking.getId(), RIDER);
        assertThat(details.departureTime()).isEqualTo(route.getDepartureTime().plus(20, ChronoUnit.MINUTES));
        assertThat(details.originalDepartureTime()).isEqualTo(route.getDepartureTime());
        assertThat(details.timeChangeCount()).isEqualTo(1);
    }

    // ---- reminders and the auto-cancel follow the new time ---------------------------------------

    @Test
    void remindersAreSentAgainForTheNewTime() {
        RoutePost route = routeDepartingIn(120);
        confirmed(route, RIDER);
        RoutePost r = reload(route);
        r.setReminderSentAt(Instant.now());
        r.setDriverReminderSentAt(Instant.now());
        r.setStartNudgeSentAt(Instant.now());
        routePostRepository.save(r);

        routeTimeChangeService.change(route.getId(), DRIVER, plus(15));

        RoutePost moved = reload(route);
        assertThat(moved.getReminderSentAt()).isNull();
        assertThat(moved.getDriverReminderSentAt()).isNull();
        assertThat(moved.getStartNudgeSentAt()).isNull();
    }

    @Test
    void aTripPastItsOldTimeIsNotAutoCancelledOnceMovedLater() {
        RoutePost route = routeDepartingIn(120);
        Booking booking = confirmed(route, RIDER);
        departedMinutesAgo(route, 40);

        routeTimeChangeService.change(route.getId(), DRIVER, at(Instant.now().plus(20, ChronoUnit.MINUTES)));
        expiryJob.run();

        assertThat(reload(route).getStatus()).isEqualTo(RouteStatus.PUBLISHED);
        assertThat(reload(booking).getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void aLateChangeCloseToDepartureWithRidersIsRecordedAsLastMinuteForOps() {
        RoutePost route = routeDepartingIn(20);
        confirmed(route, RIDER);
        routeTimeChangeService.change(route.getId(), DRIVER, plus(15));
        routeTimeChangeService.change(route.getId(), DRIVER, plus(15));
        RoutePost empty = routeDepartingIn(400);
        routeTimeChangeService.change(empty.getId(), DRIVER, plus(15));

        var flagged = routeTimeChangeService.frequentLastMinuteChangers(30, 2);

        assertThat(flagged).singleElement().satisfies(d -> {
            assertThat(d.driverId()).isEqualTo(DRIVER);
            assertThat(d.lastMinuteChanges()).isEqualTo(2);
            assertThat(d.totalChanges()).isEqualTo(3);
        });
        assertThat(routeTimeChangeService.frequentLastMinuteChangers(30, 3)).isEmpty();
    }

    // ---- what can't be changed -----------------------------------------------------------------

    @Test
    void onlyTheTripsOwnDriverCanChangeIt() {
        RoutePost route = routeDepartingIn(120);
        assertThatThrownBy(() -> routeTimeChangeService.change(route.getId(), RIDER, plus(10))).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> routeTimeChangeService.change("no-such-route", DRIVER, plus(10))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aStartedCancelledOrCompletedTripCannotChange() {
        for (RouteStatus status : List.of(RouteStatus.IN_PROGRESS, RouteStatus.CANCELLED, RouteStatus.COMPLETED)) {
            RoutePost route = routePostRepository.save(routeBuilder().status(status).build());
            assertThatThrownBy(() -> routeTimeChangeService.change(route.getId(), DRIVER, plus(10)))
                    .as(status.name())
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("hasn't started");
        }
    }

    @Test
    void aFullTripCanStillMoveLater() {
        RoutePost route = routePostRepository.save(routeBuilder().seatCapacity(1).seatsAvailable(1).build());
        confirmed(route, RIDER);
        assertThat(reload(route).getStatus()).isEqualTo(RouteStatus.FULL);

        routeTimeChangeService.change(route.getId(), DRIVER, plus(10));

        assertThat(reload(route).getStatus()).isEqualTo(RouteStatus.FULL);
    }

    @Test
    void theRequestMustSayExactlyOneOfTheNewTimeOrMinutes() {
        RoutePost route = routeDepartingIn(120);
        assertThatThrownBy(() -> routeTimeChangeService.change(route.getId(), DRIVER, new ChangeTripTimeRequest(null, null)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> routeTimeChangeService.change(route.getId(), DRIVER,
                new ChangeTripTimeRequest(Instant.now().plus(3, ChronoUnit.HOURS), 10)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void theNewTimeMustBeInTheFutureDifferentAndWithinThreeDays() {
        RoutePost route = routeDepartingIn(120);
        assertThatThrownBy(() -> routeTimeChangeService.change(route.getId(), DRIVER, at(Instant.now().minus(1, ChronoUnit.MINUTES))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already passed");
        assertThatThrownBy(() -> routeTimeChangeService.change(route.getId(), DRIVER, at(route.getDepartureTime())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already the departure time");
        assertThatThrownBy(() -> routeTimeChangeService.change(route.getId(), DRIVER, at(Instant.now().plus(4, ChronoUnit.DAYS))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("days ahead");
    }

    @Test
    void theNewTimeCannotClashWithAnotherOfTheDriversTrips() {
        RoutePost route = routeDepartingIn(120);
        routePostRepository.save(routeBuilder().departureTime(Instant.now().plus(300, ChronoUnit.MINUTES))
                .estimatedDurationMinutes(60).build());

        assertThatThrownBy(() -> routeTimeChangeService.change(route.getId(), DRIVER, at(Instant.now().plus(300, ChronoUnit.MINUTES))))
                .isInstanceOf(BadRequestException.class);
        assertThat(reload(route).getDepartureTime()).as("nothing changed").isEqualTo(route.getDepartureTime());
        assertThat(routeTimeChangeRepository.findAll()).isEmpty();
    }

    @Test
    void aRiderWhoCancelledIsNotToldOrMoved() {
        RoutePost route = routeDepartingIn(300);
        Booking booking = confirmed(route, RIDER);
        bookingService.cancelByRider(booking.getId(), RIDER);
        clearInvocations(kafkaTemplate);

        RouteTimeChangeResponse response = routeTimeChangeService.change(route.getId(), DRIVER, at(route.getDepartureTime().minus(30, ChronoUnit.MINUTES)));

        assertThat(response.ridersTold()).isZero();
        assertThat(reload(booking).getDepartureShiftMinutes()).isZero();
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.ROUTE_TIME_CHANGED), any());
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static ChangeTripTimeRequest plus(int minutes) {
        return new ChangeTripTimeRequest(null, minutes);
    }

    private static ChangeTripTimeRequest at(Instant departure) {
        return new ChangeTripTimeRequest(departure, null);
    }

    private RoutePost routeDepartingIn(int minutes) {
        return routePostRepository.save(routeBuilder().departureTime(Instant.now().plus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS)).build());
    }

    private RoutePost.RoutePostBuilder<?, ?> routeBuilder() {
        return RoutePost.builder()
                .driverId(DRIVER)
                .vehicleId("vehicle-1")
                .originLabel("Church X")
                .originLat(6.45).originLng(3.39)
                .destinationLabel("Ajah")
                .destinationLat(6.44).destinationLng(3.40)
                .departureTime(Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS))
                .seatCapacity(4)
                .seatsAvailable(4)
                .farePerSeat(FARE)
                .status(RouteStatus.PUBLISHED);
    }

    private Booking book(RoutePost route, String riderId) {
        return bookingService.reserveSeats(route.getId(), riderId, new BookSeatsRequest(null, 1, "Pickup point", 6.45, 3.39));
    }

    private Booking confirmed(RoutePost route, String riderId) {
        Booking booking = book(route, riderId);
        bookingService.confirm(booking.getId());
        return reload(booking);
    }

    private Booking onlyPaid(RoutePost route) {
        return bookingRepository.findByRoutePostId(route.getId()).stream()
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED).findFirst().orElseThrow();
    }

    private void departedMinutesAgo(RoutePost route, int minutes) {
        RoutePost r = reload(route);
        r.setDepartureTime(Instant.now().minus(minutes, ChronoUnit.MINUTES));
        routePostRepository.save(r);
    }

    private RoutePost reload(RoutePost route) {
        return routePostRepository.findById(route.getId()).orElseThrow();
    }

    private Booking reload(Booking booking) {
        return bookingRepository.findById(booking.getId()).orElseThrow();
    }
}
