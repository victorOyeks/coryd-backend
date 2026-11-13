package org.coryd.trip.service;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.OpsTripStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.common.kafka.events.RouteCancelledEvent;
import org.coryd.trip.client.IdentityServiceClient;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.config.StuckTripProperties;
import org.coryd.trip.config.policy.CancellationPolicy;
import org.coryd.trip.config.policy.DriverVerificationPolicy;
import org.coryd.trip.dto.BookSeatsRequest;
import org.coryd.trip.dto.LocationResponse;
import org.coryd.trip.dto.OpsActionResult;
import org.coryd.trip.dto.OpsCancelRequest;
import org.coryd.trip.dto.OpsCancellationFilter;
import org.coryd.trip.dto.OpsCancellationInfo;
import org.coryd.trip.dto.OpsResolveRequest;
import org.coryd.trip.dto.OpsTimelineEntry;
import org.coryd.trip.dto.OpsTripFilter;
import org.coryd.trip.dto.OpsTripItem;
import org.coryd.trip.dto.OpsTripSort;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.CancellationFault;
import org.coryd.trip.entity.CancellationLog;
import org.coryd.trip.entity.CancellationSource;
import org.coryd.trip.entity.OpsAuditLog;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.BookingStatusHistoryRepository;
import org.coryd.trip.repository.CancellationLogRepository;
import org.coryd.trip.repository.OpsAuditLogRepository;
import org.coryd.trip.repository.RatingRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.scheduler.RouteExpiryJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Ops Console's trip and booking actions - cancel a trip, cancel a seat, resolve a stuck trip - and the queries that
 * go with them (filters, stuck queue, timeline, Cancellations Log), against a throwaway Postgres built from trip-service's
 * own Liquibase migrations. Kafka is mocked: "the refund" here is the one BOOKING_CANCELLED payment-service refunds from.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@org.springframework.test.context.TestPropertySource(properties = "identity.verification.enforcement=OFF")
@Import({BookingService.class, RouteService.class, UserBlockService.class, RouteMatcher.class, org.coryd.trip.config.GeoCapabilities.class,
        RouteTemplateService.class, org.coryd.trip.config.policy.IdentityVerificationPolicy.class, TripScheduleService.class,
        OpsTripQueryService.class, CancellationLogService.class, StuckTripProperties.class, OpsTripActionService.class,
        CancellationPolicy.class, MatchingProperties.class, NotifyMeService.class, DemandService.class,
        org.coryd.trip.config.DemandProperties.class, org.coryd.trip.config.TimeChangeProperties.class,
        org.coryd.trip.config.SavingsProperties.class, OpsDriverStatsService.class, DriverStatsPublisher.class,
        OpsRiderStatsService.class, RiderStatsPublisher.class, OpsRiderTripService.class})
class OpsTripActionsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    private static final String DRIVER = "driver-1";
    private static final String OTHER_DRIVER = "driver-2";
    private static final String RIDER = "rider-1";
    private static final String OTHER_RIDER = "rider-2";
    private static final String THIRD_RIDER = "rider-3";
    private static final String ADMIN = "admin-1";
    private static final BigDecimal FARE = new BigDecimal("1200.00");

    @Autowired BookingService bookingService;
    @Autowired RouteService routeService;
    @Autowired OpsTripActionService opsTripActionService;
    @Autowired OpsTripQueryService opsTripQueryService;
    @Autowired StuckTripProperties stuckTripProperties;
    @Autowired BookingRepository bookingRepository;
    @Autowired RoutePostRepository routePostRepository;
    @Autowired BookingStatusHistoryRepository historyRepository;
    @Autowired RatingRepository ratingRepository;
    @Autowired CancellationLogRepository cancellationLogRepository;
    @Autowired OpsAuditLogRepository opsAuditLogRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired org.coryd.trip.repository.SuspendedUserRepository suspendedUserRepository;

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
        assertThat(jdbcTemplate.queryForObject("select current_database()", String.class))
                .as("tests must only ever run against the Testcontainers database").isEqualTo(postgres.getDatabaseName());
        cancellationLogRepository.deleteAll();
        opsAuditLogRepository.deleteAll();
        ratingRepository.deleteAll();
        historyRepository.deleteAll();
        bookingRepository.deleteAll();
        routePostRepository.deleteAll();
        suspendedUserRepository.deleteAll();
        stuckTripProperties.setGraceHours(3);
        clearInvocations(kafkaTemplate);
    }

    // ---- cancel a whole trip -------------------------------------------------------------------------------------

    @Test
    void cancellingATripRefundsEveryPaidRiderOnceTellsEveryoneAndIsAudited() {
        RoutePost route = route(4);
        Booking paid1 = paid(route, RIDER);
        Booking paid2 = paid(route, OTHER_RIDER);
        Booking unpaid = book(route, THIRD_RIDER);
        clearInvocations(kafkaTemplate);

        OpsActionResult result = opsTripActionService.cancelTrip(route.getId(), cancel("Driver phoned in sick", CancellationFault.DRIVER));

        assertThat(result.statusBefore()).isEqualTo("PUBLISHED");
        assertThat(result.statusAfter()).isEqualTo("CANCELLED");
        assertThat(result.refunds()).extracting(OpsActionResult.Refund::bookingId).containsExactlyInAnyOrder(paid1.getId(), paid2.getId());
        assertThat(result.refunds()).allMatch(r -> r.refund().compareTo(FARE) == 0 && r.fee().signum() == 0);
        assertThat(status(route)).isEqualTo(RouteStatus.CANCELLED);
        assertThat(bookingStatus(paid1)).isEqualTo(BookingStatus.CANCELLED_DRIVER);
        assertThat(bookingStatus(unpaid)).isEqualTo(BookingStatus.CANCELLED_DRIVER);

        List<BookingCancelledEvent> events = bookingCancelledEvents();
        assertThat(events).hasSize(3).allMatch(e -> RouteService.CANCELLED_BY_OPS_TRIP.equals(e.getCancelledBy()) && !e.isFeeApplied());
        assertThat(events.stream().filter(BookingCancelledEvent::isPaid).map(BookingCancelledEvent::getBookingId))
                .as("each paid seat refunded exactly once").containsExactlyInAnyOrder(paid1.getId(), paid2.getId());
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.ROUTE_CANCELLED),
                argThat(e -> RouteService.CANCELLED_BY_OPS_TRIP.equals(((RouteCancelledEvent) e).getCancelledBy())
                        && ((RouteCancelledEvent) e).getAffectedBookingIds().size() == 2));

        OpsAuditLog audit = opsAuditLogRepository.findById(result.auditId()).orElseThrow();
        assertThat(audit.getActorId()).isEqualTo(ADMIN);
        assertThat(audit.getActorName()).isEqualTo("Ada Ops");
        assertThat(audit.getAction()).isEqualTo(OpsAuditLog.Action.CANCEL_TRIP);
        assertThat(audit.getTargetId()).isEqualTo(route.getId());
        assertThat(audit.getReason()).isEqualTo("Driver phoned in sick");
        assertThat(audit.getFault()).isEqualTo(CancellationFault.DRIVER);
        assertThat(audit.getBeforeState()).contains("\"routeStatus\":\"PUBLISHED\"");
        assertThat(audit.getAfterState()).contains("\"routeStatus\":\"CANCELLED\"").contains(paid1.getId());

        CancellationLog routeRow = cancellationLogRepository.findByRoutePostIdOrderByCancelledAtAsc(route.getId()).stream()
                .filter(c -> c.getKind() == CancellationLog.Kind.ROUTE).findFirst().orElseThrow();
        assertThat(routeRow.getSource()).isEqualTo(CancellationSource.OPS);
        assertThat(routeRow.getActorName()).isEqualTo("Ada Ops");
        assertThat(routeRow.getBookingsAffected()).isEqualTo(2);
        assertThat(routeRow.getRefundExpected()).isEqualByComparingTo("2400.00");
        assertThat(routeRow.getMinutesBeforeDeparture()).isGreaterThan(60 * 23);
        assertThat(cancellationLogRepository.findByParentIdIn(List.of(routeRow.getId())))
                .extracting(CancellationLog::getBookingId).containsExactlyInAnyOrder(paid1.getId(), paid2.getId());

        clearInvocations(kafkaTemplate);
        assertThatThrownBy(() -> opsTripActionService.cancelTrip(route.getId(), cancel("again", CancellationFault.DRIVER)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already cancelled");
        verify(kafkaTemplate, never()).send(anyString(), any());
        assertThat(opsAuditLogRepository.count()).as("a refused action isn't audited").isEqualTo(1);
    }

    @Test
    void aCompletedTripCantBeCancelledAndATripCantBeTheRidersFault() {
        RoutePost route = route(4);
        Booking booking = paid(route, RIDER);
        start(route);
        pickUp(booking);
        bookingService.completeRoute(route.getId(), DRIVER);
        clearInvocations(kafkaTemplate);

        assertThatThrownBy(() -> opsTripActionService.cancelTrip(route.getId(), cancel("too late", CancellationFault.PLATFORM)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already completed");
        assertThatThrownBy(() -> opsTripActionService.cancelBooking(booking.getId(), cancel("too late", CancellationFault.PLATFORM)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already completed");

        RoutePost other = route(4);
        assertThatThrownBy(() -> opsTripActionService.cancelTrip(other.getId(), cancel("rider was rude", CancellationFault.RIDER)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("rider's fault");
        assertThatThrownBy(() -> opsTripActionService.cancelTrip(other.getId(), cancel("   ", CancellationFault.DRIVER)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("reason is required");
        assertThat(status(other)).isEqualTo(RouteStatus.PUBLISHED);
        verify(kafkaTemplate, never()).send(anyString(), any());
        assertThat(opsAuditLogRepository.count()).isZero();
    }

    // ---- cancel one seat -----------------------------------------------------------------------------------------

    @Test
    void riderFaultInsideTheFreeWindowKeepsTheLateFeeLikeTheRidersOwnCancel() {
        RoutePost route = routeAt(Instant.now().plus(90, ChronoUnit.MINUTES));
        Booking booking = paid(route, RIDER);
        int seatsBefore = seatsLeft(route);
        clearInvocations(kafkaTemplate);

        OpsActionResult result = opsTripActionService.cancelBooking(booking.getId(), cancel("Rider asked support to cancel", CancellationFault.RIDER));

        BigDecimal fee = new BigDecimal("300.00"); // 25% - CancellationPolicy's default
        assertThat(result.statusAfter()).isEqualTo("CANCELLED_RIDER");
        assertThat(result.refunds()).singleElement().satisfies(r -> {
            assertThat(r.fee()).isEqualByComparingTo(fee);
            assertThat(r.refund()).isEqualByComparingTo("900.00");
        });
        assertThat(bookingStatus(booking)).isEqualTo(BookingStatus.CANCELLED_RIDER);
        assertThat(seatsLeft(route)).isEqualTo(seatsBefore + 1);
        BookingCancelledEvent event = bookingCancelledEvents().getFirst();
        assertThat(bookingCancelledEvents()).hasSize(1);
        assertThat(event.getCancelledBy()).isEqualTo(BookingService.CANCELLED_BY_OPS);
        assertThat(event.isFeeApplied()).isTrue();
        assertThat(event.getFeeAmount()).isEqualByComparingTo(fee);
        assertThat(event.isPaid()).isTrue();
        assertThat(event.getDriverId()).as("the driver is told the seat is free").isEqualTo(DRIVER);

        CancellationLog row = cancellationLogRepository.findAll().getFirst();
        assertThat(row.getFeeAmount()).isEqualByComparingTo(fee);
        assertThat(row.getRefundExpected()).isEqualByComparingTo("900.00");
        assertThat(row.getFault()).isEqualTo(CancellationFault.RIDER);
        assertThat(row.getReason()).isEqualTo("Rider asked support to cancel");
        assertThat(row.getBookingStatusBefore()).isEqualTo("CONFIRMED");
        assertThat(historyRepository.findByBookingIdOrderByCreatedAtAsc(booking.getId()).getLast().getNote())
                .contains("CoRyd support").contains("RIDER fault").contains("Rider asked support to cancel");
    }

    @Test
    void aWaivedFeeOrAnyOtherFaultRefundsInFull() {
        RoutePost route = routeAt(Instant.now().plus(90, ChronoUnit.MINUTES));
        Booking waived = paid(route, RIDER);
        Booking driverFault = paid(route, OTHER_RIDER);
        Booking platformFault = paid(route, THIRD_RIDER);
        clearInvocations(kafkaTemplate);

        OpsActionResult w = opsTripActionService.cancelBooking(waived.getId(),
                new OpsCancelRequest("Goodwill", CancellationFault.RIDER, true, ADMIN, "Ada Ops"));
        OpsActionResult d = opsTripActionService.cancelBooking(driverFault.getId(), cancel("Driver no longer going that way", CancellationFault.DRIVER));
        OpsActionResult p = opsTripActionService.cancelBooking(platformFault.getId(), cancel("App error double-booked", CancellationFault.PLATFORM));

        for (OpsActionResult r : List.of(w, d, p)) {
            assertThat(r.refunds().getFirst().refund()).isEqualByComparingTo(FARE);
            assertThat(r.refunds().getFirst().fee()).isEqualByComparingTo("0");
        }
        assertThat(w.statusAfter()).isEqualTo("CANCELLED_RIDER");
        assertThat(d.statusAfter()).isEqualTo("CANCELLED_DRIVER");
        assertThat(p.statusAfter()).isEqualTo("CANCELLED_DRIVER");
        assertThat(bookingCancelledEvents()).hasSize(3).noneMatch(BookingCancelledEvent::isFeeApplied);
        assertThat(opsAuditLogRepository.findById(w.auditId()).orElseThrow().getAfterState()).contains("\"feeWaived\":true");
    }

    @Test
    void aSeatIsRefundedExactlyOnceEvenWhenOpsAndTheRiderCancelTogether() throws Exception {
        RoutePost route = route(4);
        Booking booking = paid(route, RIDER);
        clearInvocations(kafkaTemplate);

        List<Future<Boolean>> outcomes = runConcurrently(List.of(
                () -> opsTripActionService.cancelBooking(booking.getId(), cancel("Rider called", CancellationFault.PLATFORM)) != null,
                () -> opsTripActionService.cancelBooking(booking.getId(), cancel("Rider called", CancellationFault.PLATFORM)) != null,
                () -> {
                    bookingService.cancelByRider(booking.getId(), RIDER);
                    return true;
                }));
        int succeeded = 0;
        for (Future<Boolean> outcome : outcomes) {
            try {
                outcome.get();
                succeeded++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(BadRequestException.class);
            }
        }

        assertThat(succeeded).isEqualTo(1);
        assertThat(bookingCancelledEvents()).as("one refund, whoever won").hasSize(1);
        assertThat(cancellationLogRepository.count()).isEqualTo(1);
        assertThat(seatsLeft(route)).isEqualTo(4);
    }

    @Test
    void anUnpaidHoldIsJustLetGoWithNothingToRefund() {
        RoutePost route = route(4);
        Booking held = book(route, RIDER);
        clearInvocations(kafkaTemplate);

        OpsActionResult result = opsTripActionService.cancelBooking(held.getId(), cancel("Duplicate hold", CancellationFault.PLATFORM));

        assertThat(result.refunds()).isEmpty();
        assertThat(result.statusBefore()).isEqualTo("HELD");
        assertThat(bookingStatus(held)).isEqualTo(BookingStatus.CANCELLED_RIDER);
        assertThat(seatsLeft(route)).isEqualTo(4);
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.BOOKING_CANCELLED), any());
        assertThat(cancellationLogRepository.count()).as("an unpaid hold isn't a cancellation").isZero();
        assertThat(opsAuditLogRepository.count()).isEqualTo(1);

        assertThatThrownBy(() -> opsTripActionService.cancelBooking(held.getId(), cancel("again", CancellationFault.PLATFORM)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("CANCELLED_RIDER");
    }

    // ---- stuck trips ---------------------------------------------------------------------------------------------

    @Test
    void onlyATripPastItsExpectedArrivalPlusTheGraceIsStuck() {
        RoutePost fresh = startedRoute(RIDER, Instant.now().minus(50, ChronoUnit.MINUTES), 40);
        RoutePost stuck = startedRoute(OTHER_RIDER, Instant.now().minus(5, ChronoUnit.HOURS), 40);

        var page = opsTripQueryService.stuck(PageRequest.of(0, 20));
        assertThat(page.content()).extracting(s -> s.trip().routePostId()).containsExactly(stuck.getId());
        var item = page.content().getFirst();
        assertThat(item.awaitingPickup()).isEqualTo(1);
        assertThat(item.minutesPastExpectedArrival()).isBetween(255L, 265L);
        assertThat(item.stuckSince()).isEqualTo(item.expectedArrivalAt().plus(3, ChronoUnit.HOURS));
        assertThat(opsTripQueryService.counts((String) null).stuck()).isEqualTo(1);
        assertThat(opsTripQueryService.trip(stuck.getId()).actions().resolve()).isTrue();
        assertThat(opsTripQueryService.trip(fresh.getId()).actions().resolve()).isFalse();

        assertThatThrownBy(() -> opsTripActionService.resolve(fresh.getId(), resolve(OpsResolveRequest.Outcome.COMPLETED, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("isn't stuck");
        RoutePost published = route(4);
        assertThatThrownBy(() -> opsTripActionService.resolve(published.getId(), resolve(OpsResolveRequest.Outcome.COMPLETED, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("PUBLISHED");

        stuckTripProperties.setGraceHours(0);
        assertThat(opsTripQueryService.stuck(PageRequest.of(0, 20)).content()).as("the grace is configurable").hasSize(2);
    }

    @Test
    void resolvingAsCompletedSettlesRidersAboardAndRefundsThoseNeverPickedUp() {
        RoutePost route = route(4);
        Booking aboard = paid(route, RIDER);
        Booking neverPickedUp = paid(route, OTHER_RIDER);
        start(route);
        pickUp(aboard);
        makeStuck(route);
        clearInvocations(kafkaTemplate);

        OpsActionResult result = opsTripActionService.resolve(route.getId(), resolve(OpsResolveRequest.Outcome.COMPLETED, null));

        assertThat(result.statusAfter()).isEqualTo("COMPLETED");
        assertThat(result.completed()).containsExactly(aboard.getId());
        assertThat(result.refunds()).extracting(OpsActionResult.Refund::bookingId).containsExactly(neverPickedUp.getId());
        assertThat(status(route)).isEqualTo(RouteStatus.COMPLETED);
        assertThat(routePostRepository.findById(route.getId()).orElseThrow().getCompletedAt()).isNotNull();
        assertThat(bookingStatus(aboard)).isEqualTo(BookingStatus.COMPLETED);
        assertThat(bookingStatus(neverPickedUp)).isEqualTo(BookingStatus.CANCELLED_DRIVER);
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.TRIP_COMPLETED), any());
        assertThat(bookingCancelledEvents()).singleElement().satisfies(e -> {
            assertThat(e.getBookingId()).isEqualTo(neverPickedUp.getId());
            assertThat(e.isPaid()).isTrue();
            assertThat(e.isFeeApplied()).isFalse();
        });
        OpsAuditLog audit = opsAuditLogRepository.findById(result.auditId()).orElseThrow();
        assertThat(audit.getAction()).isEqualTo(OpsAuditLog.Action.RESOLVE_STUCK_COMPLETED);
        assertThat(audit.getBeforeState()).contains("IN_PROGRESS");

        clearInvocations(kafkaTemplate);
        assertThatThrownBy(() -> opsTripActionService.resolve(route.getId(), resolve(OpsResolveRequest.Outcome.COMPLETED, null)))
                .isInstanceOf(BadRequestException.class);
        verify(kafkaTemplate, never()).send(anyString(), any());

        List<OpsTimelineEntry> timeline = opsTripQueryService.trip(route.getId()).timeline();
        assertThat(timeline).extracting(OpsTimelineEntry::type)
                .contains("POSTED", "BOOKED", "STARTED", "PICKED_UP", "BOOKING_COMPLETED", "BOOKING_CANCELLED", "COMPLETED", "OPS_ACTION");
        assertThat(timeline.stream().filter(t -> t.type().equals("COMPLETED")).findFirst().orElseThrow().actor()).isEqualTo("OPS");
        assertThat(timeline).isSortedAccordingTo(java.util.Comparator.comparing(OpsTimelineEntry::at));
    }

    @Test
    void resolvingAsCompletedCanMarkThoseNeverPickedUpAsNoShows() {
        RoutePost route = route(4);
        Booking neverPickedUp = paid(route, OTHER_RIDER);
        start(route);
        makeStuck(route);
        clearInvocations(kafkaTemplate);

        OpsActionResult result = opsTripActionService.resolve(route.getId(), new OpsResolveRequest(OpsResolveRequest.Outcome.COMPLETED,
                "Driver confirmed rider never came", null, BookingService.UnboardedOutcome.NO_SHOW, ADMIN, "Ada Ops"));

        assertThat(result.noShows()).containsExactly(neverPickedUp.getId());
        assertThat(bookingStatus(neverPickedUp)).isEqualTo(BookingStatus.NO_SHOW);
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.BOOKING_CANCELLED), any());
    }

    @Test
    void resolvingAsCancelledRefundsEveryPaidRider() {
        RoutePost route = route(4);
        Booking aboard = paid(route, RIDER);
        start(route);
        pickUp(aboard);
        makeStuck(route);
        clearInvocations(kafkaTemplate);

        OpsActionResult result = opsTripActionService.resolve(route.getId(),
                resolve(OpsResolveRequest.Outcome.CANCELLED, CancellationFault.DRIVER));

        assertThat(result.action()).isEqualTo("RESOLVE_STUCK_CANCELLED");
        assertThat(status(route)).isEqualTo(RouteStatus.CANCELLED);
        assertThat(bookingCancelledEvents()).singleElement().satisfies(e -> assertThat(e.isPaid()).isTrue());
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.TRIP_COMPLETED), any());
        assertThat(cancellationLogRepository.findAll()).extracting(CancellationLog::getFault).containsOnly(CancellationFault.DRIVER);
    }

    // ---- cancellations log ---------------------------------------------------------------------------------------

    @Test
    void theLogHasEveryKindOfCancellationWithCountsAndFilters() {
        RoutePost r1 = route(4);
        Booking byRider = paid(r1, RIDER);
        bookingService.cancelByRider(byRider.getId(), RIDER);

        RoutePost r2 = route(4);
        paid(r2, OTHER_RIDER);
        routeService.cancel(r2.getId(), DRIVER);

        RoutePost r3 = route(4);
        paid(r3, THIRD_RIDER);
        departAt(r3, Instant.now().minus(1, ChronoUnit.HOURS));
        new RouteExpiryJob(routePostRepository, routeService, stuckTripProperties).run();

        RoutePost r4 = route(4);
        Booking byOps = paid(r4, RIDER);
        opsTripActionService.cancelBooking(byOps.getId(), cancel("Support ticket 42", CancellationFault.PLATFORM));

        OpsCancellationFilter all = new OpsCancellationFilter(null, null, null, null, null, null, null, null, null);
        var counts = opsTripQueryService.cancellationCounts(all);
        assertThat(counts.all()).as("trips folded with their seats").isEqualTo(4);
        assertThat(counts.rider()).isEqualTo(1);
        assertThat(counts.driver()).isEqualTo(1);
        assertThat(counts.systemExpiry()).isEqualTo(1);
        assertThat(counts.ops()).isEqualTo(1);

        var page = opsTripQueryService.cancellations(all, PageRequest.of(0, 20));
        assertThat(page.totalElements()).isEqualTo(4);
        assertThat(page.content()).isSortedAccordingTo(java.util.Comparator.comparing(OpsCancellationInfo::cancelledAt).reversed());
        OpsCancellationInfo expired = page.content().stream().filter(c -> c.source() == CancellationSource.SYSTEM_EXPIRY).findFirst().orElseThrow();
        assertThat(expired.kind()).isEqualTo(CancellationLog.Kind.ROUTE);
        assertThat(expired.childBookingIds()).hasSize(1);
        assertThat(expired.fault()).isEqualTo(CancellationFault.DRIVER);
        assertThat(expired.minutesBeforeDeparture()).isNegative();
        OpsCancellationInfo ops = page.content().stream().filter(c -> c.source() == CancellationSource.OPS).findFirst().orElseThrow();
        assertThat(ops.actorName()).isEqualTo("Ada Ops");
        assertThat(ops.reason()).isEqualTo("Support ticket 42");
        assertThat(ops.refundExpected()).isEqualByComparingTo(FARE);

        var seats = opsTripQueryService.cancellations(new OpsCancellationFilter(null, null, CancellationLog.Kind.BOOKING,
                null, null, null, null, null, null), PageRequest.of(0, 20));
        assertThat(seats.totalElements()).as("every seat, a trip's own included").isEqualTo(4);
        assertThat(opsTripQueryService.cancellations(new OpsCancellationFilter(null, null, null, null, null, null, RIDER, null, null),
                PageRequest.of(0, 20)).totalElements()).isEqualTo(2);
        assertThat(opsTripQueryService.cancellations(new OpsCancellationFilter(null, null, null, null, null, null, null, null, "ticket"),
                PageRequest.of(0, 20)).totalElements()).isEqualTo(1);
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
        assertThat(opsTripQueryService.cancellationCounts(new OpsCancellationFilter(null, null, null, today.plusDays(1), null,
                null, null, null, null)).all()).isZero();
        assertThat(opsTripQueryService.cancellationCounts(new OpsCancellationFilter(null, CancellationFault.DRIVER, null, today, today,
                null, null, null, null)).all()).isEqualTo(2);
    }

    @Test
    void theBackfillRebuildsOlderCancellationsFromWhatTheTablesSay() throws Exception {
        RoutePost r1 = route(4);
        Booking byRider = paid(r1, RIDER);
        bookingService.cancelByRider(byRider.getId(), RIDER);
        RoutePost r2 = route(4);
        Booking onDriverCancel = paid(r2, OTHER_RIDER);
        routeService.cancel(r2.getId(), DRIVER);
        cancellationLogRepository.deleteAll();

        String sql = new ClassPathResource("db/changelog/033-ops-trips-and-bookings.sql").getContentAsString(StandardCharsets.UTF_8);
        String backfill = sql.substring(sql.indexOf("--changeset coryd:092"))
                + "\n" + new ClassPathResource("db/changelog/034-cancellation-log-route-totals.sql").getContentAsString(StandardCharsets.UTF_8);
        for (String statement : backfill.split(";\\s*\\n")) {
            String body = statement.lines().filter(l -> !l.trim().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).trim();
            if (!body.isEmpty()) {
                jdbcTemplate.execute(body);
            }
        }

        List<CancellationLog> rows = cancellationLogRepository.findAll();
        assertThat(rows).allMatch(CancellationLog::isBackfilled);
        CancellationLog routeRow = rows.stream().filter(c -> c.getKind() == CancellationLog.Kind.ROUTE).findFirst().orElseThrow();
        assertThat(routeRow.getRoutePostId()).isEqualTo(r2.getId());
        assertThat(routeRow.getSource()).isEqualTo(CancellationSource.DRIVER);
        assertThat(routeRow.getBookingsAffected()).isEqualTo(1);
        CancellationLog seat = rows.stream().filter(c -> onDriverCancel.getId().equals(c.getBookingId())).findFirst().orElseThrow();
        assertThat(seat.getParentId()).isEqualTo(routeRow.getId());
        // 093: the trip row carries its seats' totals, so a trip with a paid seat no longer reads "Not paid".
        assertThat(routeRow.isPaid()).isTrue();
        assertThat(routeRow.getSeatCount()).isEqualTo(seat.getSeatCount());
        assertThat(routeRow.getFareTotal()).isEqualByComparingTo(seat.getFareTotal());
        assertThat(routeRow.getRefundExpected()).isEqualByComparingTo(seat.getRefundExpected());
        CancellationLog riderRow = rows.stream().filter(c -> byRider.getId().equals(c.getBookingId())).findFirst().orElseThrow();
        assertThat(riderRow.getSource()).isEqualTo(CancellationSource.RIDER);
        assertThat(riderRow.getFault()).isEqualTo(CancellationFault.RIDER);
        assertThat(riderRow.getRefundExpected()).isEqualByComparingTo(FARE);
        assertThat(rows).hasSize(3);
    }

    // ---- trips table, detail, live -------------------------------------------------------------------------------

    @Test
    void theTripsTableFiltersByLagosDayDriverRiderAndReferenceAndSortsInTheDatabase() {
        ZoneId lagos = ZoneId.of("Africa/Lagos");
        LocalDate day = LocalDate.now(lagos).plusDays(3);
        RoutePost cheapEarly = routePostRepository.save(routeBuilder(4).farePerSeat(new BigDecimal("500.00"))
                .departureTime(day.atTime(0, 30).atZone(lagos).toInstant()).build());
        RoutePost dearLate = routePostRepository.save(routeBuilder(4).farePerSeat(new BigDecimal("2000.00"))
                .departureTime(day.atTime(23, 30).atZone(lagos).toInstant()).build());
        RoutePost nextDay = routePostRepository.save(routeBuilder(4).driverId(OTHER_DRIVER)
                .departureTime(day.plusDays(1).atTime(0, 10).atZone(lagos).toInstant()).build());
        paid(dearLate, RIDER);

        OpsTripFilter thatDay = new OpsTripFilter(null, null, day, day, null, null, null);
        assertThat(ids(opsTripQueryService.trips(thatDay, OpsTripSort.FARE_PER_SEAT, Sort.Direction.ASC, PageRequest.of(0, 20))))
                .containsExactly(cheapEarly.getId(), dearLate.getId());
        assertThat(ids(opsTripQueryService.trips(thatDay, null, null, PageRequest.of(0, 20))))
                .as("newest departure first by default").containsExactly(dearLate.getId(), cheapEarly.getId());
        assertThat(ids(opsTripQueryService.trips(new OpsTripFilter(null, null, null, null, OTHER_DRIVER, null, null), null, null,
                PageRequest.of(0, 20)))).containsExactly(nextDay.getId());
        assertThat(ids(opsTripQueryService.trips(new OpsTripFilter(null, null, null, null, null, RIDER, null), null, null,
                PageRequest.of(0, 20)))).containsExactly(dearLate.getId());
        String ref = "T-" + nextDay.getId().replace("-", "").substring(0, 6).toUpperCase();
        assertThat(ids(opsTripQueryService.trips(new OpsTripFilter(null, null, null, null, null, null, ref), null, null,
                PageRequest.of(0, 20)))).containsExactly(nextDay.getId());
        var page = opsTripQueryService.trips(new OpsTripFilter(null, null, null, null, null, null, null),
                OpsTripSort.DEPARTURE_TIME, Sort.Direction.ASC, PageRequest.of(1, 2));
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(ids(page)).containsExactly(nextDay.getId());

        RoutePost cancelled = route(4);
        routeService.cancel(cancelled.getId(), DRIVER);
        var counts = opsTripQueryService.tripCounts(new OpsTripFilter(OpsTripStatus.CANCELLED, null, null, null, null, null, null));
        assertThat(counts.all()).as("counts ignore the status filter").isEqualTo(4);
        assertThat(counts.published()).isEqualTo(3);
        assertThat(counts.cancelled()).isEqualTo(1);
        assertThat(opsTripQueryService.trips(new OpsTripFilter(OpsTripStatus.ACTIVE, List.of(RouteStatus.CANCELLED), null, null, null, null, null),
                null, null, PageRequest.of(0, 20)).totalElements()).as("a route status outside the tab matches nothing").isZero();
    }

    @Test
    void theDetailShowsEachPaidSeatsCancellationAndTheFeeOpsWouldKeep() {
        RoutePost route = routeAt(Instant.now().plus(90, ChronoUnit.MINUTES));
        Booking stays = paid(route, RIDER);
        Booking goes = paid(route, OTHER_RIDER);
        book(route, THIRD_RIDER);
        opsTripActionService.cancelBooking(goes.getId(), cancel("Rider asked", CancellationFault.RIDER));

        var detail = opsTripQueryService.trip(route.getId());

        assertThat(detail.passengers()).extracting(p -> p.bookingId()).containsExactly(stays.getId(), goes.getId());
        var staying = detail.passengers().getFirst();
        assertThat(staying.cancellable()).isTrue();
        assertThat(staying.riderFaultFeeNow()).isEqualByComparingTo("300.00");
        var gone = detail.passengers().get(1);
        assertThat(gone.cancellable()).isFalse();
        assertThat(gone.cancellation().source()).isEqualTo(CancellationSource.OPS);
        assertThat(gone.cancellation().feeAmount()).isEqualByComparingTo("300.00");
        assertThat(detail.actions().cancel()).isTrue();
        assertThat(detail.actions().resolve()).isFalse();
        assertThat(detail.timeline()).anySatisfy(t -> {
            assertThat(t.type()).isEqualTo("BOOKING_CANCELLED");
            assertThat(t.actor()).isEqualTo("OPS");
            assertThat(t.actorName()).isEqualTo("Ada Ops");
            assertThat(t.detail()).contains("Rider asked").contains("rider fault").contains("₦300.00");
        });
        assertThat(detail.timeline()).filteredOn(t -> t.type().equals("BOOKED")).hasSize(2);
    }

    @Test
    void liveTripsCarryTheLastSeenPointAndHowStaleItIs() {
        RoutePost route = startedRoute(RIDER, Instant.now().minus(2, ChronoUnit.HOURS), 40);
        Instant seenAt = Instant.now().minus(10, ChronoUnit.MINUTES);
        when(locationService.lastSeenRouteLocation(route.getId()))
                .thenReturn(new LocationResponse(false, 6.5, 3.4, seenAt, "Last position is stale"));
        when(locationService.peekRouteLocation(route.getId())).thenReturn(LocationResponse.unavailable("Waiting"));

        var item = opsTripQueryService.live(null).getFirst();

        assertThat(item.lastSeen().lat()).isEqualTo(6.5);
        assertThat(item.locationStale()).isTrue();
        assertThat(item.locationAgeSeconds()).isBetween(595L, 610L);
        assertThat(item.overdue()).isTrue();
        assertThat(item.stuck()).isFalse();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static OpsCancelRequest cancel(String reason, CancellationFault fault) {
        return new OpsCancelRequest(reason, fault, null, ADMIN, "Ada Ops");
    }

    private static OpsResolveRequest resolve(OpsResolveRequest.Outcome outcome, CancellationFault fault) {
        return new OpsResolveRequest(outcome, "Driver unreachable, riders confirmed by phone", fault, null, ADMIN, "Ada Ops");
    }

    private List<BookingCancelledEvent> bookingCancelledEvents() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeast(0)).send(eq(KafkaTopics.BOOKING_CANCELLED), captor.capture());
        return captor.getAllValues().stream().map(BookingCancelledEvent.class::cast).toList();
    }

    private RoutePost route(int seats) {
        return routePostRepository.save(routeBuilder(seats).build());
    }

    private RoutePost routeAt(Instant departure) {
        return routePostRepository.save(routeBuilder(4).departureTime(departure).build());
    }

    private RoutePost.RoutePostBuilder<?, ?> routeBuilder(int seats) {
        return RoutePost.builder()
                .driverId(DRIVER)
                .vehicleId("vehicle-1")
                .originLabel("Campos Memorial Mini Stadium")
                .originLat(6.45).originLng(3.39)
                .destinationLabel("Obalende Bridge")
                .destinationLat(6.44).destinationLng(3.40)
                .departureTime(Instant.now().plus(1, ChronoUnit.DAYS))
                .seatCapacity(seats)
                .seatsAvailable(seats)
                .farePerSeat(FARE)
                .status(RouteStatus.PUBLISHED);
    }

    private Booking book(RoutePost route, String riderId) {
        return bookingService.reserveSeats(route.getId(), riderId, new BookSeatsRequest(null, 1, "Pickup point", 6.45, 3.39));
    }

    private Booking paid(RoutePost route, String riderId) {
        Booking booking = book(route, riderId);
        bookingService.confirm(booking.getId());
        return booking;
    }

    /** Start only opens inside the active window, so bring departure into it first. */
    private void start(RoutePost route) {
        departAt(route, Instant.now().plus(10, ChronoUnit.MINUTES));
        routeService.startTrip(route.getId(), DRIVER);
    }

    /** A trip with one paid rider, started at startedAt with the given drive time. */
    private RoutePost startedRoute(String riderId, Instant startedAt, int durationMinutes) {
        RoutePost route = routePostRepository.save(routeBuilder(4).estimatedDurationMinutes(durationMinutes).build());
        paid(route, riderId);
        start(route);
        backdate(route, startedAt);
        return route;
    }

    private void makeStuck(RoutePost route) {
        backdate(route, Instant.now().minus(6, ChronoUnit.HOURS));
    }

    private void backdate(RoutePost route, Instant startedAt) {
        RoutePost r = routePostRepository.findById(route.getId()).orElseThrow();
        r.setStartedAt(startedAt);
        r.setDepartureTime(startedAt);
        routePostRepository.save(r);
    }

    private void departAt(RoutePost route, Instant departure) {
        RoutePost r = routePostRepository.findById(route.getId()).orElseThrow();
        r.setDepartureTime(departure);
        routePostRepository.save(r);
    }

    private void pickUp(Booking booking) {
        bookingService.markPickedUp(booking.getId(), DRIVER, bookingRepository.findById(booking.getId()).orElseThrow().getPickupCode());
    }

    private RouteStatus status(RoutePost route) {
        return routePostRepository.findById(route.getId()).orElseThrow().getStatus();
    }

    private BookingStatus bookingStatus(Booking booking) {
        return bookingRepository.findById(booking.getId()).orElseThrow().getStatus();
    }

    private int seatsLeft(RoutePost route) {
        return routePostRepository.findById(route.getId()).orElseThrow().getSeatsAvailable();
    }

    private static List<String> ids(org.coryd.common.response.PagedResponse<OpsTripItem> page) {
        return page.content().stream().map(OpsTripItem::routePostId).toList();
    }

    private static <T> List<Future<T>> runConcurrently(List<Callable<T>> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(() -> {
                go.await();
                return task.call();
            }));
        }
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
        return futures;
    }
}
