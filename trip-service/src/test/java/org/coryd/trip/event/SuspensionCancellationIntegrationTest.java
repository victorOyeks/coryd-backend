package org.coryd.trip.event;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.common.kafka.events.RouteCancelledEvent;
import org.coryd.common.kafka.events.UserReinstatedEvent;
import org.coryd.common.kafka.events.UserSuspendedEvent;
import org.coryd.trip.client.IdentityServiceClient;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.config.policy.CancellationPolicy;
import org.coryd.trip.config.policy.DriverVerificationPolicy;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.BookingStatusHistoryRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.SuspendedUserRepository;
import org.coryd.trip.service.BookingService;
import org.coryd.trip.service.ChatService;
import org.coryd.trip.service.DemandService;
import org.coryd.trip.service.DriverStatsPublisher;
import org.coryd.trip.service.LiveSearchSessionRegistry;
import org.coryd.trip.service.LocationService;
import org.coryd.trip.service.MapsService;
import org.coryd.trip.service.NotifyMeService;
import org.coryd.trip.service.OpsDriverStatsService;
import org.coryd.trip.service.OpsRiderStatsService;
import org.coryd.trip.service.OpsRiderTripService;
import org.coryd.trip.service.OpsTripQueryService;
import org.coryd.trip.service.RiderStatsPublisher;
import org.coryd.trip.service.RouteMatcher;
import org.coryd.trip.service.RouteService;
import org.coryd.trip.service.RouteTemplateService;
import org.coryd.trip.service.ShareTokenService;
import org.coryd.trip.service.TripScheduleService;
import org.coryd.trip.service.UserBlockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

/**
 * What suspending someone does to their trips (product decision): everything upcoming is cancelled - a suspended driver's
 * published routes with every rider refunded, a suspended rider's seats including a pickup already under way (driver
 * en route or waiting), with a full refund and the driver told the seat opened up - while a trip already in progress is
 * never interrupted. Against a throwaway Postgres built from trip-service's migrations.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@TestPropertySource(properties = "identity.verification.enforcement=OFF")
@Import({UserSuspensionEventListener.class, BookingService.class, RouteService.class, UserBlockService.class, RouteMatcher.class,
        org.coryd.trip.config.GeoCapabilities.class, RouteTemplateService.class, org.coryd.trip.config.policy.IdentityVerificationPolicy.class,
        TripScheduleService.class, OpsTripQueryService.class, CancellationPolicy.class, MatchingProperties.class, NotifyMeService.class,
        DemandService.class, org.coryd.trip.config.DemandProperties.class, org.coryd.trip.config.TimeChangeProperties.class,
        org.coryd.trip.config.SavingsProperties.class, OpsDriverStatsService.class, DriverStatsPublisher.class, OpsRiderStatsService.class,
        RiderStatsPublisher.class, OpsRiderTripService.class})
class SuspensionCancellationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    private static final String DRIVER = "driver-s1";
    private static final String OTHER_DRIVER = "driver-s2";
    private static final String RIDER = "rider-s1";
    private static final String OTHER_RIDER = "rider-s2";

    @Autowired UserSuspensionEventListener listener;
    @Autowired BookingRepository bookingRepository;
    @Autowired RoutePostRepository routePostRepository;
    @Autowired BookingStatusHistoryRepository historyRepository;
    @Autowired SuspendedUserRepository suspendedUserRepository;
    @Autowired org.coryd.trip.repository.RatingRepository ratingRepository;

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;
    @MockBean ProfileServiceClient profileServiceClient;
    @MockBean org.coryd.trip.client.PaymentServiceClient paymentServiceClient;
    @MockBean IdentityServiceClient identityServiceClient;
    @MockBean ShareTokenService shareTokenService;
    @MockBean LocationService locationService;
    @MockBean MapsService mapsService;
    @MockBean ChatService chatService;
    @MockBean LiveSearchSessionRegistry liveSearchSessionRegistry;
    @MockBean DriverVerificationPolicy driverVerificationPolicy;

    @BeforeEach
    void cleanSlate() {
        suspendedUserRepository.deleteAll();
        ratingRepository.deleteAll();
        historyRepository.deleteAll();
        bookingRepository.deleteAll();
        routePostRepository.deleteAll();
        clearInvocations(kafkaTemplate);
    }

    @Test
    void aSuspendedDriversUpcomingRoutesAreCancelledWithRefundsButATripUnderWayIsLeftAlone() {
        RoutePost tomorrow = route(DRIVER, RouteStatus.PUBLISHED);
        Booking paid = booking(tomorrow, RIDER, BookingStatus.CONFIRMED);
        Booking held = booking(tomorrow, OTHER_RIDER, BookingStatus.HELD);
        RoutePost full = route(DRIVER, RouteStatus.FULL);
        Booking paidOnFull = booking(full, OTHER_RIDER, BookingStatus.CONFIRMED);
        RoutePost underWay = route(DRIVER, RouteStatus.IN_PROGRESS);
        Booking onBoard = booking(underWay, RIDER, BookingStatus.IN_PROGRESS);
        RoutePost someoneElses = route(OTHER_DRIVER, RouteStatus.PUBLISHED);
        Booking untouched = booking(someoneElses, RIDER, BookingStatus.CONFIRMED);

        listener.onSuspended(suspended(DRIVER));

        assertThat(status(tomorrow)).isEqualTo(RouteStatus.CANCELLED);
        assertThat(status(full)).isEqualTo(RouteStatus.CANCELLED);
        assertThat(status(underWay)).as("a trip under way is never interrupted").isEqualTo(RouteStatus.IN_PROGRESS);
        assertThat(status(someoneElses)).isEqualTo(RouteStatus.PUBLISHED);
        assertThat(status(paid)).isEqualTo(BookingStatus.CANCELLED_DRIVER);
        assertThat(status(paidOnFull)).isEqualTo(BookingStatus.CANCELLED_DRIVER);
        assertThat(status(onBoard)).isEqualTo(BookingStatus.IN_PROGRESS);
        assertThat(status(untouched)).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(suspendedUserRepository.existsById(DRIVER)).isTrue();

        // every affected rider is told (and refunded when they'd paid); the driver is told each route was cancelled
        List<BookingCancelledEvent> cancelled = sent(KafkaTopics.BOOKING_CANCELLED, BookingCancelledEvent.class);
        assertThat(cancelled).extracting(BookingCancelledEvent::getBookingId)
                .containsExactlyInAnyOrder(paid.getId(), held.getId(), paidOnFull.getId());
        assertThat(cancelled).allSatisfy(e -> {
            assertThat(e.getCancelledBy()).isEqualTo(UserSuspensionEventListener.CANCELLED_BY);
            assertThat(e.isRiderAccountCancelled()).as("the riders did nothing - the driver isn't told 'a seat opened up'").isFalse();
        });
        assertThat(cancelled).filteredOn(e -> e.getBookingId().equals(paid.getId())).singleElement()
                .satisfies(e -> assertThat(e.isPaid()).isTrue());
        assertThat(sent(KafkaTopics.ROUTE_CANCELLED, RouteCancelledEvent.class)).extracting(RouteCancelledEvent::getRoutePostId)
                .containsExactlyInAnyOrder(tomorrow.getId(), full.getId());
    }

    @Test
    void aSuspendedRidersUpcomingSeatsAndPickupsAreCancelledInFullButARideUnderWayIsLeftAlone() {
        Booking confirmed = booking(route(DRIVER, RouteStatus.PUBLISHED), RIDER, BookingStatus.CONFIRMED);
        Booking driverOnTheWay = booking(route(DRIVER, RouteStatus.IN_PROGRESS), RIDER, BookingStatus.EN_ROUTE_TO_PICKUP);
        Booking driverWaiting = booking(route(OTHER_DRIVER, RouteStatus.IN_PROGRESS), RIDER, BookingStatus.AT_PICKUP);
        Booking unpaid = booking(route(OTHER_DRIVER, RouteStatus.PUBLISHED), RIDER, BookingStatus.HELD);
        Booking onBoard = booking(route(OTHER_DRIVER, RouteStatus.IN_PROGRESS), RIDER, BookingStatus.IN_PROGRESS);
        Booking someoneElse = booking(route(DRIVER, RouteStatus.PUBLISHED), OTHER_RIDER, BookingStatus.CONFIRMED);

        listener.onSuspended(suspended(RIDER));

        assertThat(status(confirmed)).isEqualTo(BookingStatus.CANCELLED_RIDER);
        assertThat(status(driverOnTheWay)).as("pickup under way - cancelled").isEqualTo(BookingStatus.CANCELLED_RIDER);
        assertThat(status(driverWaiting)).isEqualTo(BookingStatus.CANCELLED_RIDER);
        assertThat(status(onBoard)).as("already riding - left to finish").isEqualTo(BookingStatus.IN_PROGRESS);
        assertThat(status(someoneElse)).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(status(unpaid)).as("the unpaid hold is released").isNotEqualTo(BookingStatus.HELD);

        List<BookingCancelledEvent> cancelled = sent(KafkaTopics.BOOKING_CANCELLED, BookingCancelledEvent.class);
        assertThat(cancelled).filteredOn(BookingCancelledEvent::isPaid).extracting(BookingCancelledEvent::getBookingId)
                .containsExactlyInAnyOrder(confirmed.getId(), driverOnTheWay.getId(), driverWaiting.getId());
        assertThat(cancelled).filteredOn(BookingCancelledEvent::isPaid).allSatisfy(e -> {
            assertThat(e.isFeeApplied()).as("refunded in full").isFalse();
            assertThat(e.isRiderAccountCancelled()).as("so the driver is told the seat opened up").isTrue();
            assertThat(e.getDriverId()).isNotNull();
            assertThat(e.getRiderId()).isEqualTo(RIDER);
        });
    }

    @Test
    void reinstatingLetsThemBookAndPostAgain() {
        listener.onSuspended(suspended(RIDER));
        assertThat(suspendedUserRepository.existsById(RIDER)).isTrue();

        listener.onReinstated(UserReinstatedEvent.builder().userId(RIDER).reinstatedBy("ops-1").reinstatedAt(Instant.now()).build());

        assertThat(suspendedUserRepository.existsById(RIDER)).isFalse();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static UserSuspendedEvent suspended(String userId) {
        return UserSuspendedEvent.builder().userId(userId).reason("Threatened someone").suspendedBy("ops-1").suspendedAt(Instant.now()).build();
    }

    private RoutePost route(String driverId, RouteStatus status) {
        return routePostRepository.save(RoutePost.builder()
                .driverId(driverId)
                .vehicleId("vehicle-1")
                .originLabel("Ikorodu").originLat(6.45).originLng(3.39)
                .destinationLabel("Obalende").destinationLat(6.44).destinationLng(3.40)
                .departureTime(status == RouteStatus.IN_PROGRESS ? Instant.now().minus(10, ChronoUnit.MINUTES) : Instant.now().plus(1, ChronoUnit.DAYS))
                .seatCapacity(4).seatsAvailable(3)
                .farePerSeat(new BigDecimal("1200.00"))
                .status(status)
                .build());
    }

    private Booking booking(RoutePost route, String riderId, BookingStatus status) {
        boolean paid = status != BookingStatus.HELD && status != BookingStatus.RESERVED;
        return bookingRepository.save(Booking.builder()
                .routePostId(route.getId())
                .riderId(riderId)
                .seatCount(1)
                .fareTotal(new BigDecimal("1200.00"))
                .status(status)
                .holdExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES))
                .confirmedAt(paid ? Instant.now().minus(1, ChronoUnit.HOURS) : null)
                .build());
    }

    private RouteStatus status(RoutePost route) {
        return routePostRepository.findById(route.getId()).orElseThrow().getStatus();
    }

    private BookingStatus status(Booking booking) {
        return bookingRepository.findById(booking.getId()).orElseThrow().getStatus();
    }

    private <T> List<T> sent(String topic, Class<T> type) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeast(0)).send(eq(topic), captor.capture());
        return captor.getAllValues().stream().filter(type::isInstance).map(type::cast).toList();
    }
}
