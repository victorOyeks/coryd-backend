package org.coryd.trip.service;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.BookingCancelledEvent;
import org.coryd.trip.client.IdentityServiceClient;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.config.policy.CancellationPolicy;
import org.coryd.trip.config.policy.DriverVerificationPolicy;
import org.coryd.trip.dto.BookSeatsRequest;
import org.coryd.trip.dto.RateRequest;
import org.coryd.trip.dto.TripReceiptResponse;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.BookingStatusHistoryRepository;
import org.coryd.trip.repository.RatingRepository;
import org.coryd.trip.repository.RoutePostRepository;
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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Runs the booking lifecycle against a throwaway Postgres container built from trip-service's own
 * Liquibase migrations - never a shared or real database. Not transactional per test, so the
 * concurrency cases commit for real across threads, exactly as two app requests would.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({BookingService.class, RouteService.class, TripScheduleService.class, OpsTripQueryService.class, CancellationPolicy.class, MatchingProperties.class})
class BookingFlowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String DRIVER = "driver-1";
    private static final String RIDER = "rider-1";
    private static final String OTHER_RIDER = "rider-2";
    private static final BigDecimal FARE = new BigDecimal("1200.00");

    @Autowired BookingService bookingService;
    @Autowired RouteService routeService;
    @Autowired BookingRepository bookingRepository;
    @Autowired RoutePostRepository routePostRepository;
    @Autowired BookingStatusHistoryRepository historyRepository;
    @Autowired RatingRepository ratingRepository;
    @Autowired DataSource dataSource;
    @Autowired org.coryd.trip.repository.DriverVerificationCacheRepository driverVerificationCacheRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired OpsTripQueryService opsTripQueryService;

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
    void cleanSlate() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getURL())
                    .as("tests must only ever run against the Testcontainers database")
                    .isEqualTo(postgres.getJdbcUrl());
        }
        driverVerificationCacheRepository.deleteAll();
        org.mockito.Mockito.when(paymentServiceClient.postingStatus(org.mockito.ArgumentMatchers.anyString())).thenReturn(standing(true, "0", false));
        ratingRepository.deleteAll();
        historyRepository.deleteAll();
        bookingRepository.deleteAll();
        routePostRepository.deleteAll();
        clearInvocations(kafkaTemplate);
    }

    // ---- payout account before posting ---------------------------------------------------------

    /** The eligibility response echoes the policy's settings - the mocked policy has none until told. */
    private void policySettings() {
        org.mockito.Mockito.when(driverVerificationPolicy.getEnforcement()).thenReturn(DriverVerificationPolicy.Enforcement.GRACE);
        org.mockito.Mockito.when(driverVerificationPolicy.getPrerequisiteLevel()).thenReturn(DriverVerificationPolicy.PrerequisiteLevel.SUBMITTED);
    }

    @Test
    void aDriverWithNoBankAccountCannotPostEvenOnceApproved() {
        policySettings();
        driverVerificationCacheRepository.save(new org.coryd.trip.entity.DriverVerificationCache(DRIVER, true));
        org.mockito.Mockito.when(paymentServiceClient.postingStatus(DRIVER)).thenReturn(standing(false, "0", false));

        var eligibility = routeService.postingEligibility(DRIVER);
        assertThat(eligibility.canPostRoutes()).isFalse();
        assertThat(eligibility.payoutAccountOk()).isFalse();
        assertThat(eligibility.blockedReason()).isEqualTo("PAYOUT_ACCOUNT_REQUIRED");
        assertThatThrownBy(() -> routeService.post(DRIVER, DRIVER, postRequest(Instant.now().plus(1, ChronoUnit.DAYS))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("bank account");
        assertThat(routePostRepository.findAll()).isEmpty();
    }

    @Test
    void identityStillComesBeforeTheBankAccount() {
        policySettings();
        org.mockito.Mockito.when(driverVerificationPolicy.requiresApprovedPrerequisites()).thenReturn(false);
        org.mockito.Mockito.when(profileServiceClient.fetchOnboarding(DRIVER)).thenThrow(new RuntimeException("profile down"));
        org.mockito.Mockito.when(paymentServiceClient.postingStatus(DRIVER)).thenReturn(standing(false, "0", false));

        var eligibility = routeService.postingEligibility(DRIVER);

        assertThat(eligibility.blockedReason()).isEqualTo("IDENTITY_REQUIRED");
        assertThat(eligibility.payoutAccountOk()).isFalse();
    }

    @Test
    void anUnreachablePaymentServiceDoesNotBlockPosting() {
        policySettings();
        driverVerificationCacheRepository.save(new org.coryd.trip.entity.DriverVerificationCache(DRIVER, true));
        org.mockito.Mockito.when(paymentServiceClient.postingStatus(DRIVER)).thenReturn(null);

        var eligibility = routeService.postingEligibility(DRIVER);

        assertThat(eligibility.canPostRoutes()).isTrue();
        assertThat(eligibility.blockedReason()).isNull();
    }

    @Test
    void paymentHistoriesGetEachBookingsRouteAndUnknownBookingsAreLeftOut() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);

        var summaries = bookingService.getRouteSummaries(List.of(booking.getId(), "no-such-booking"));

        assertThat(summaries).singleElement().satisfies(s -> {
            assertThat(s.bookingId()).isEqualTo(booking.getId());
            assertThat(s.routePostId()).isEqualTo(route.getId());
            assertThat(s.originLabel()).isEqualTo(route.getOriginLabel());
            assertThat(s.destinationLabel()).isEqualTo(route.getDestinationLabel());
        });
    }

    @Test
    void aDriverOwingTooMuchMustPayItBackBeforePosting() {
        policySettings();
        driverVerificationCacheRepository.save(new org.coryd.trip.entity.DriverVerificationCache(DRIVER, true));
        org.mockito.Mockito.when(paymentServiceClient.postingStatus(DRIVER)).thenReturn(standing(true, "12500.00", true));

        var eligibility = routeService.postingEligibility(DRIVER);

        assertThat(eligibility.canPostRoutes()).isFalse();
        assertThat(eligibility.blockedReason()).isEqualTo("AMOUNT_OWED");
        assertThat(eligibility.amountOwed()).isEqualByComparingTo("12500.00");
        assertThatThrownBy(() -> routeService.post(DRIVER, DRIVER, postRequest(Instant.now().plus(1, ChronoUnit.DAYS))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("12500.00");
    }

    @Test
    void owingLessThanTheLimitDoesNotBlock() {
        policySettings();
        driverVerificationCacheRepository.save(new org.coryd.trip.entity.DriverVerificationCache(DRIVER, true));
        org.mockito.Mockito.when(paymentServiceClient.postingStatus(DRIVER)).thenReturn(standing(true, "800.00", false));

        var eligibility = routeService.postingEligibility(DRIVER);

        assertThat(eligibility.canPostRoutes()).isTrue();
        assertThat(eligibility.amountOwed()).isEqualByComparingTo("800.00");
    }

    private static org.coryd.trip.client.PaymentServiceClient.PostingStatus standing(boolean hasAccount, String owed, boolean overLimit) {
        return new org.coryd.trip.client.PaymentServiceClient.PostingStatus(hasAccount, new java.math.BigDecimal(owed), overLimit);
    }

    // ---- booking the same ride twice ------------------------------------------------------------

    @Test
    void retriedBookingReturnsTheSameHoldInsteadOfASecondOne() {
        RoutePost route = route(8);

        Booking first = book(route, RIDER, 1);
        Booking retry = book(route, RIDER, 1);

        assertThat(retry.getId()).isEqualTo(first.getId());
        assertThat(bookingRepository.findByRoutePostId(route.getId())).hasSize(1);
        assertThat(seatsLeft(route)).isEqualTo(7);
    }

    @Test
    void concurrentTapsFromOneRiderCreateExactlyOneBooking() throws Exception {
        RoutePost route = route(8);

        List<Future<Booking>> results = runConcurrently(10, () -> book(route, RIDER, 1));
        for (Future<Booking> f : results) {
            f.get();
        }

        assertThat(bookingRepository.findByRoutePostId(route.getId())).hasSize(1);
        assertThat(seatsLeft(route)).isEqualTo(7);
    }

    @Test
    void riderWithAConfirmedTripCannotBookAnother() {
        RoutePost first = route(4);
        RoutePost second = route(4);
        confirm(book(first, RIDER, 1));

        assertThatThrownBy(() -> book(second, RIDER, 1)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> book(first, RIDER, 1)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void bookingAnotherRouteReleasesTheAbandonedHold() {
        RoutePost first = route(4);
        RoutePost second = route(4);
        Booking abandoned = book(first, RIDER, 2);

        book(second, RIDER, 1);

        assertThat(status(abandoned)).isEqualTo(BookingStatus.CANCELLED_RIDER);
        assertThat(seatsLeft(first)).isEqualTo(4);
        assertThat(seatsLeft(second)).isEqualTo(3);
    }

    @Test
    void cannotBookOwnCancelledOrDepartedRoute() {
        RoutePost own = route(4);
        assertThatThrownBy(() -> book(own, DRIVER, 1)).isInstanceOf(BadRequestException.class);

        RoutePost cancelled = route(4);
        routeService.cancel(cancelled.getId(), DRIVER);
        assertThatThrownBy(() -> book(cancelled, RIDER, 1)).isInstanceOf(BadRequestException.class);

        RoutePost departed = routePostRepository.save(routeBuilder(4).departureTime(Instant.now().minus(1, ChronoUnit.HOURS)).build());
        assertThatThrownBy(() -> book(departed, RIDER, 1)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void lastSeatGoesToExactlyOneOfManyRiders() throws Exception {
        RoutePost route = route(1);
        List<String> riders = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            riders.add("racer-" + i);
        }

        List<Future<Booking>> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(riders.size());
        CountDownLatch go = new CountDownLatch(1);
        for (String rider : riders) {
            results.add(pool.submit(() -> {
                go.await();
                return book(route, rider, 1);
            }));
        }
        go.countDown();
        int won = 0;
        for (Future<Booking> f : results) {
            try {
                f.get();
                won++;
            } catch (Exception ignored) {
                // "Not enough seats available" for everyone else
            }
        }
        pool.shutdown();

        assertThat(won).isEqualTo(1);
        assertThat(seatsLeft(route)).isZero();
        assertThat(routePostRepository.findById(route.getId()).orElseThrow().getStatus()).isEqualTo(RouteStatus.FULL);
    }

    // ---- seats can never be released twice ----------------------------------------------------

    @Test
    void failureWebhookAfterHoldExpiredDoesNotReleaseTheSeatAgain() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 2);

        bookingService.expireUnpaid(reload(booking));
        bookingService.releaseOnPaymentFailure(booking.getId());

        assertThat(seatsLeft(route)).isEqualTo(4);
    }

    @Test
    void failureWebhookAfterPaymentSucceededLeavesTheBookingConfirmed() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);
        confirm(booking);

        bookingService.releaseOnPaymentFailure(booking.getId());

        assertThat(status(booking)).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(seatsLeft(route)).isEqualTo(3);
    }

    @Test
    void cancellingTwiceDoesNotMintSeats() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 2);
        confirm(booking);

        bookingService.cancelByRider(booking.getId(), RIDER);
        assertThatThrownBy(() -> bookingService.cancelByRider(booking.getId(), RIDER)).isInstanceOf(BadRequestException.class);

        assertThat(seatsLeft(route)).isEqualTo(4);
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.BOOKING_CANCELLED), any());
    }

    @Test
    void cancellingAnUnpaidHoldReleasesItWithoutARefundEvent() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);

        bookingService.cancelByRider(booking.getId(), RIDER);

        assertThat(seatsLeft(route)).isEqualTo(4);
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.BOOKING_CANCELLED), any());
    }

    // ---- payment confirmation --------------------------------------------------------------------

    @Test
    void paymentAfterHoldExpiredIsRefundedNotConfirmed() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);
        bookingService.expireUnpaid(reload(booking));

        bookingService.confirm(booking.getId());

        assertThat(status(booking)).isEqualTo(BookingStatus.CANCELLED_RIDER);
        verify(kafkaTemplate).send(eq(KafkaTopics.BOOKING_CANCELLED),
                argThat(e -> ((BookingCancelledEvent) e).getBookingId().equals(booking.getId())));
    }

    @Test
    void expirySweepAndPaymentRacingNeverBothWin() throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            cleanSlate();
            RoutePost route = route(2);
            Booking booking = book(route, RIDER, 1);
            Booking stale = reload(booking);

            runConcurrently(2, new Callable<>() {
                private int calls;
                @Override
                public Object call() {
                    if (next()) {
                        bookingService.expireUnpaid(stale);
                    } else {
                        bookingService.confirm(booking.getId());
                    }
                    return null;
                }
                private synchronized boolean next() {
                    return calls++ == 0;
                }
            }).forEach(this::await);

            BookingStatus end = status(booking);
            if (end == BookingStatus.CONFIRMED) {
                assertThat(seatsLeft(route)).as("a confirmed booking keeps its seat").isEqualTo(1);
                verify(kafkaTemplate, never()).send(eq(KafkaTopics.BOOKING_CANCELLED), any());
            } else {
                assertThat(end).isEqualTo(BookingStatus.CANCELLED_RIDER);
                assertThat(seatsLeft(route)).isEqualTo(2);
                verify(kafkaTemplate).send(eq(KafkaTopics.BOOKING_CANCELLED), any());
            }
        }
    }

    @Test
    void repeatedPaymentWebhookMidTripDoesNotRefundTheRider() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);
        confirm(booking);
        start(route);
        pickUp(booking);

        bookingService.confirm(booking.getId());

        assertThat(status(booking)).isEqualTo(BookingStatus.IN_PROGRESS);
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.BOOKING_CANCELLED), any());
    }

    @Test
    void paymentForARouteCancelledMeanwhileIsRefunded() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);
        routePostRepository.findById(route.getId()).ifPresent(r -> {
            r.setStatus(RouteStatus.CANCELLED);
            routePostRepository.save(r);
        });

        bookingService.confirm(booking.getId());

        assertThat(status(booking)).isEqualTo(BookingStatus.CANCELLED_DRIVER);
        verify(kafkaTemplate).send(eq(KafkaTopics.BOOKING_CANCELLED), any());
    }

    // ---- trip progress ---------------------------------------------------------------------------

    @Test
    void driverCannotPickUpOrCompleteACancelledBooking() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);
        confirm(booking);
        bookingService.cancelByRider(booking.getId(), RIDER);
        start(route);

        assertThatThrownBy(() -> pickUp(booking)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> bookingService.completeTrip(booking.getId(), DRIVER)).isInstanceOf(BadRequestException.class);
        assertThat(status(booking)).isEqualTo(BookingStatus.CANCELLED_RIDER);
    }

    @Test
    void doubleTappedCompletePaysTheDriverOnce() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);
        confirm(booking);
        start(route);
        pickUp(booking);

        bookingService.completeTrip(booking.getId(), DRIVER);
        bookingService.completeTrip(booking.getId(), DRIVER);
        bookingService.completeRoute(route.getId(), DRIVER);

        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.TRIP_COMPLETED), any());
    }

    @Test
    void completedRouteCannotBeCancelled() {
        RoutePost route = route(4);
        confirm(book(route, RIDER, 1));
        start(route);
        bookingService.markNoShow(bookingRepository.findByRoutePostId(route.getId()).get(0).getId(), DRIVER);
        bookingService.completeRoute(route.getId(), DRIVER);

        assertThatThrownBy(() -> routeService.cancel(route.getId(), DRIVER)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void routeCancellationReportsOnlyThePaidBookingsItRefunded() {
        RoutePost route = route(4);
        Booking paid = book(route, RIDER, 1);
        confirm(paid);
        Booking stillPaying = book(route, OTHER_RIDER, 1);

        routeService.cancel(route.getId(), DRIVER);

        verify(kafkaTemplate).send(eq(KafkaTopics.ROUTE_CANCELLED), argThat(e ->
                ((org.coryd.common.kafka.events.RouteCancelledEvent) e).getAffectedBookingIds().equals(List.of(paid.getId()))));
        verify(kafkaTemplate).send(eq(KafkaTopics.BOOKING_CANCELLED), argThat(e -> {
            BookingCancelledEvent event = (BookingCancelledEvent) e;
            return event.getBookingId().equals(paid.getId()) && event.isPaid();
        }));
        verify(kafkaTemplate).send(eq(KafkaTopics.BOOKING_CANCELLED), argThat(e -> {
            BookingCancelledEvent event = (BookingCancelledEvent) e;
            return event.getBookingId().equals(stillPaying.getId()) && !event.isPaid();
        }));
    }

    @Test
    void routeCancelledTwiceRefundsEachBookingOnce() {
        RoutePost route = route(4);
        confirm(book(route, RIDER, 1));

        routeService.cancel(route.getId(), DRIVER);
        routeService.cancel(route.getId(), DRIVER);

        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.BOOKING_CANCELLED), any());
    }

    // ---- receipt and history - the reported 8-seat bug -------------------------------------------

    @Test
    void receiptCountsOnlyTheRidersActuallyCarried() {
        RoutePost route = route(8);
        // Mayowa's flaky network: three holds that never got paid, then gave up.
        for (int i = 0; i < 3; i++) {
            Booking attempt = book(route, "mayowa", 1);
            bookingService.expireUnpaid(reload(attempt));
        }
        // Musa paid, then cancelled.
        Booking musa = book(route, "musa", 1);
        confirm(musa);
        bookingService.cancelByRider(musa.getId(), "musa");
        // Amaka paid and rode.
        Booking amaka = book(route, "amaka", 1);
        confirm(amaka);
        // Tunde paid but never turned up.
        Booking tunde = book(route, "tunde", 2);
        confirm(tunde);
        start(route);
        pickUp(amaka);
        bookingService.markNoShow(tunde.getId(), DRIVER);
        bookingService.completeRoute(route.getId(), DRIVER);

        TripReceiptResponse receipt = bookingService.getTripReceipt(route.getId(), DRIVER);

        assertThat(receipt.seatsBooked()).isEqualTo(1);
        assertThat(receipt.totalEarned()).isEqualByComparingTo(FARE);
        assertThat(receipt.passengers()).extracting(p -> p.riderId()).containsExactlyInAnyOrder("amaka", "tunde");
        assertThat(receipt.passengers()).filteredOn(p -> p.riderId().equals("tunde"))
                .extracting(p -> p.status()).containsExactly(BookingStatus.NO_SHOW);

        var history = bookingService.getHistoryForDriver(DRIVER, DRIVER, null, null,
                org.springframework.data.domain.PageRequest.of(0, 20));
        assertThat(history.content().get(0).seatsBooked()).isEqualTo(1);
        assertThat(history.content().get(0).totalEarned()).isEqualByComparingTo(FARE);

        var mayowaHistory = bookingService.getHistory("mayowa", "mayowa", null, null,
                org.springframework.data.domain.PageRequest.of(0, 20));
        assertThat(mayowaHistory.content()).as("unpaid attempts are not trips").isEmpty();
    }

    // ---- who can see and do what -----------------------------------------------------------------

    @Test
    void strangersCannotReadOrActOnSomeoneElsesBooking() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);
        confirm(booking);

        assertThatThrownBy(() -> bookingService.getBookingDetails(booking.getId(), OTHER_RIDER)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> bookingService.getStatusHistory(booking.getId(), OTHER_RIDER)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> bookingService.cancelByRider(booking.getId(), OTHER_RIDER)).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> bookingService.getHistory(RIDER, OTHER_RIDER, null, null,
                org.springframework.data.domain.PageRequest.of(0, 20))).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> bookingService.markPickedUp(booking.getId(), OTHER_RIDER, "0000")).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void ratingIsTiedToTheBookingAndOncePerSide() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);
        confirm(booking);
        RateRequest fiveStars = new RateRequest(null, null, null, 5, null);

        assertThatThrownBy(() -> bookingService.rate(booking.getId(), RIDER, fiveStars))
                .as("not before the trip").isInstanceOf(BadRequestException.class);

        start(route);
        pickUp(booking);
        bookingService.completeRoute(route.getId(), DRIVER);

        assertThatThrownBy(() -> bookingService.rate(booking.getId(), OTHER_RIDER, fiveStars)).isInstanceOf(UnauthorizedException.class);
        assertThat(bookingService.rate(booking.getId(), RIDER, fiveStars).getRateeId()).isEqualTo(DRIVER);
        assertThatThrownBy(() -> bookingService.rate(booking.getId(), RIDER, fiveStars)).isInstanceOf(BadRequestException.class);
        assertThat(bookingService.rate(booking.getId(), DRIVER, fiveStars).getRateeId()).isEqualTo(RIDER);
    }

    // ---- pickup code -----------------------------------------------------------------------------

    @Test
    void driverNeedsTheRidersPickupCodeToMarkThemPickedUp() {
        RoutePost route = startedRouteWithConfirmed(RIDER);
        Booking booking = onlyBooking(route);

        assertThatThrownBy(() -> bookingService.markPickedUp(booking.getId(), DRIVER, null)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> bookingService.markPickedUp(booking.getId(), DRIVER, "12a4")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> bookingService.markPickedUp(booking.getId(), DRIVER, wrongCode(booking))).isInstanceOf(BadRequestException.class);
        assertThat(status(booking)).isEqualTo(BookingStatus.CONFIRMED);

        bookingService.markPickedUp(booking.getId(), DRIVER, reload(booking).getPickupCode());

        assertThat(status(booking)).isEqualTo(BookingStatus.IN_PROGRESS);
    }

    @Test
    void riderConfirmingTheirOwnBoardingNeedsNoCode() {
        RoutePost route = startedRouteWithConfirmed(RIDER);
        Booking booking = onlyBooking(route);

        bookingService.markPickedUp(booking.getId(), RIDER, null);

        assertThat(status(booking)).isEqualTo(BookingStatus.IN_PROGRESS);
    }

    @Test
    void codeFromAnotherBookingOrRouteIsRejected() {
        RoutePost route = route(4);
        Booking mine = book(route, RIDER, 1);
        Booking sameRoute = book(route, OTHER_RIDER, 1);
        confirm(mine);
        confirm(sameRoute);
        RoutePost otherRoute = route(4);
        Booking otherRouteBooking = book(otherRoute, "rider-3", 1);
        confirm(otherRouteBooking);
        setCode(mine, "1111");
        setCode(sameRoute, "2222");
        setCode(otherRouteBooking, "3333");
        start(route);

        assertThatThrownBy(() -> bookingService.markPickedUp(mine.getId(), DRIVER, "2222")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> bookingService.markPickedUp(mine.getId(), DRIVER, "3333")).isInstanceOf(BadRequestException.class);
        assertThat(status(mine)).isEqualTo(BookingStatus.CONFIRMED);

        bookingService.markPickedUp(mine.getId(), DRIVER, "1111");
        assertThat(status(mine)).isEqualTo(BookingStatus.IN_PROGRESS);
    }

    @Test
    void wrongCodesLockTheDriverOutButTheRiderCanStillBoard() {
        RoutePost route = startedRouteWithConfirmed(RIDER);
        Booking booking = onlyBooking(route);
        String wrong = wrongCode(booking);
        for (int i = 0; i < BookingService.MAX_PICKUP_CODE_ATTEMPTS; i++) {
            assertThatThrownBy(() -> bookingService.markPickedUp(booking.getId(), DRIVER, wrong)).isInstanceOf(BadRequestException.class);
        }

        assertThatThrownBy(() -> bookingService.markPickedUp(booking.getId(), DRIVER, reload(booking).getPickupCode()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Too many");

        bookingService.markPickedUp(booking.getId(), RIDER, null);
        assertThat(status(booking)).isEqualTo(BookingStatus.IN_PROGRESS);
    }

    @Test
    void correctCodeAfterAFewTyposStillWorks() {
        RoutePost route = startedRouteWithConfirmed(RIDER);
        Booking booking = onlyBooking(route);
        for (int i = 0; i < BookingService.MAX_PICKUP_CODE_ATTEMPTS - 1; i++) {
            assertThatThrownBy(() -> bookingService.markPickedUp(booking.getId(), DRIVER, wrongCode(booking))).isInstanceOf(BadRequestException.class);
        }

        bookingService.markPickedUp(booking.getId(), DRIVER, reload(booking).getPickupCode());

        assertThat(status(booking)).isEqualTo(BookingStatus.IN_PROGRESS);
    }

    @Test
    void attemptsResetOnceTheRightCodeIsEntered() {
        RoutePost route = startedRouteWithConfirmed(RIDER);
        Booking booking = onlyBooking(route);
        String code = reload(booking).getPickupCode();
        for (int i = 0; i < BookingService.MAX_PICKUP_CODE_ATTEMPTS - 1; i++) {
            assertThatThrownBy(() -> bookingService.markPickedUp(booking.getId(), DRIVER, wrongCode(booking))).isInstanceOf(BadRequestException.class);
        }
        bookingService.markPickedUp(booking.getId(), DRIVER, code);
        bookingService.undoManifestMark(booking.getId(), DRIVER);

        assertThat(reload(booking).getPickupCodeAttempts()).isZero();
        for (int i = 0; i < BookingService.MAX_PICKUP_CODE_ATTEMPTS - 1; i++) {
            assertThatThrownBy(() -> bookingService.markPickedUp(booking.getId(), DRIVER, wrongCode(booking))).isInstanceOf(BadRequestException.class);
        }
        bookingService.markPickedUp(booking.getId(), DRIVER, code);
        assertThat(status(booking)).isEqualTo(BookingStatus.IN_PROGRESS);
    }

    @Test
    void parallelGuessingCannotGetPastTheAttemptCap() throws Exception {
        RoutePost route = startedRouteWithConfirmed(RIDER);
        Booking booking = onlyBooking(route);
        String code = reload(booking).getPickupCode();
        List<String> guesses = new ArrayList<>();
        for (int g = 0; guesses.size() < 40; g++) {
            String guess = String.format("%04d", g);
            if (!guess.equals(code)) {
                guesses.add(guess);
            }
        }

        List<Callable<Object>> attempts = new ArrayList<>();
        for (String guess : guesses) {
            attempts.add(() -> {
                bookingService.markPickedUp(booking.getId(), DRIVER, guess);
                return null;
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(attempts.size());
        for (Future<Object> f : pool.invokeAll(attempts)) {
            assertThatThrownBy(f::get).hasCauseInstanceOf(BadRequestException.class);
        }
        pool.shutdown();

        assertThat(reload(booking).getPickupCodeAttempts()).isEqualTo(BookingService.MAX_PICKUP_CODE_ATTEMPTS);
        assertThatThrownBy(() -> bookingService.markPickedUp(booking.getId(), DRIVER, code)).hasMessageContaining("Too many");
    }

    @Test
    void onlyTheRiderIsShownThePickupCode() throws Exception {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);
        assertThat(bookingService.getBookingDetails(booking.getId(), RIDER).pickupCode()).as("no code before payment").isNull();
        confirm(booking);
        String code = reload(booking).getPickupCode();

        assertThat(code).matches("\\d{4}");
        assertThat(bookingService.getBookingDetails(booking.getId(), RIDER).pickupCode()).isEqualTo(code);
        assertThat(bookingService.getBookingDetails(booking.getId(), DRIVER).pickupCode()).isNull();
        assertThat(bookingService.getManifestForRoute(route.getId(), DRIVER).toString()).doesNotContain(code);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(reload(booking)))
                .doesNotContain("pickupCode");
        verify(kafkaTemplate).send(eq(KafkaTopics.BOOKING_CONFIRMED),
                argThat(e -> code.equals(((org.coryd.common.kafka.events.BookingConfirmedEvent) e).getPickupCode())));
    }

    @Test
    void ridersOnOneRouteGetDistinctCodes() {
        RoutePost route = route(8);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Booking b = book(route, "rider-" + i, 1);
            confirm(b);
            codes.add(reload(b).getPickupCode());
        }

        assertThat(codes).doesNotHaveDuplicates();
    }

    // ---- departure reminder ----------------------------------------------------------------------

    @Test
    void departureReminderGoesOutOnceForARouteAboutToLeave() {
        RoutePost soon = routePostRepository.save(routeBuilder(4).departureTime(Instant.now().plus(10, ChronoUnit.MINUTES)).build());
        confirm(book(soon, RIDER, 1));
        var job = new org.coryd.trip.scheduler.RouteDepartureReminderJob(routePostRepository, bookingRepository, kafkaTemplate);

        job.sendUpcomingDepartureReminders();
        job.sendUpcomingDepartureReminders();

        assertThat(routePostRepository.findById(soon.getId()).orElseThrow().getReminderSentAt()).isNotNull();
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.ROUTE_DEPARTURE_REMINDER), any());
        verify(kafkaTemplate).send(eq(KafkaTopics.ROUTE_DEPARTURE_REMINDER), argThat(e -> kind(e).equals("RIDER_GET_READY")));
    }

    @Test
    void driverGetsAGetReadyReminderWhenAScheduledRouteEntersTheActiveWindow() {
        RoutePost route = routePostRepository.save(routeBuilder(4).departureTime(Instant.now().plus(25, ChronoUnit.MINUTES)).build());
        postedAt(route, Instant.now().minus(2, ChronoUnit.HOURS));
        var job = new org.coryd.trip.scheduler.RouteDepartureReminderJob(routePostRepository, bookingRepository, kafkaTemplate);

        job.sendUpcomingDepartureReminders();
        job.sendUpcomingDepartureReminders();

        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.ROUTE_DEPARTURE_REMINDER), any());
        verify(kafkaTemplate).send(eq(KafkaTopics.ROUTE_DEPARTURE_REMINDER), argThat(e -> kind(e).equals("DRIVER_GET_READY")));
    }

    @Test
    void leaveNowRouteSkipsTheDriverGetReadyReminder() {
        routePostRepository.save(routeBuilder(4).departureTime(Instant.now().plus(25, ChronoUnit.MINUTES)).build());
        var job = new org.coryd.trip.scheduler.RouteDepartureReminderJob(routePostRepository, bookingRepository, kafkaTemplate);

        job.sendUpcomingDepartureReminders();

        verify(kafkaTemplate, never()).send(eq(KafkaTopics.ROUTE_DEPARTURE_REMINDER), any());
    }

    @Test
    void driverIsNudgedOnceWhenDepartureTimePassesUnstarted() {
        RoutePost route = route(4);
        confirm(book(route, RIDER, 1));
        departAt(route, Instant.now().minus(10, ChronoUnit.MINUTES));
        var job = new org.coryd.trip.scheduler.RouteDepartureReminderJob(routePostRepository, bookingRepository, kafkaTemplate);

        job.sendUpcomingDepartureReminders();
        job.sendUpcomingDepartureReminders();

        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.ROUTE_DEPARTURE_REMINDER),
                argThat(e -> kind(e).equals("DRIVER_START_OVERDUE")));
    }

    @Test
    void routeNeverStartedIsAutoCancelledAsNotStarted() {
        RoutePost route = route(4);
        confirm(book(route, RIDER, 1));
        departAt(route, Instant.now().minus(1, ChronoUnit.HOURS));

        new org.coryd.trip.scheduler.RouteExpiryJob(routePostRepository, routeService).run();

        assertThat(routePostRepository.findById(route.getId()).orElseThrow().getStatus()).isEqualTo(RouteStatus.CANCELLED);
        verify(kafkaTemplate).send(eq(KafkaTopics.BOOKING_CANCELLED),
                argThat(e -> "NOT_STARTED".equals(((BookingCancelledEvent) e).getCancelledBy())));
        verify(kafkaTemplate).send(eq(KafkaTopics.ROUTE_CANCELLED),
                argThat(e -> "NOT_STARTED".equals(((org.coryd.common.kafka.events.RouteCancelledEvent) e).getCancelledBy())));
    }

    // ---- upcoming vs active, and the overlap rule --------------------------------------------------

    @Test
    void riderCanHoldNonOverlappingBookings() {
        RoutePost toWork = route(4);
        RoutePost home = routeAt(toWork.getDepartureTime().plus(4, ChronoUnit.HOURS), null);

        confirm(book(toWork, RIDER, 1));
        confirm(book(home, RIDER, 1));

        assertThat(bookingRepository.findByRiderIdAndStatusIn(RIDER, List.of(BookingStatus.CONFIRMED))).hasSize(2);
    }

    @Test
    void overlappingBookingIsRejectedWithAClearMessage() {
        RoutePost first = route(4);
        RoutePost clash = routeAt(first.getDepartureTime().plus(1, ChronoUnit.HOURS), null);
        confirm(book(first, RIDER, 1));

        assertThatThrownBy(() -> book(clash, RIDER, 1))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("overlaps a ride you've booked")
                .hasMessageContaining("Trips can't overlap");
        assertThatThrownBy(() -> book(first, RIDER, 1)).hasMessageContaining("already booked a seat on this ride");
    }

    @Test
    void overlapWindowIsDurationPlusBuffer() {
        RoutePost first = routeAt(Instant.now().plus(1, ChronoUnit.DAYS), 40);
        confirm(book(first, RIDER, 1));
        Instant occupiedUntil = first.getDepartureTime().plus(70, ChronoUnit.MINUTES);

        assertThatThrownBy(() -> book(routeAt(occupiedUntil.minus(1, ChronoUnit.MINUTES), null), RIDER, 1))
                .isInstanceOf(BadRequestException.class);
        confirm(book(routeAt(occupiedUntil, null), RIDER, 1));
    }

    @Test
    void riderCannotBookARideDuringARouteTheyAreDriving() {
        RoutePost theirOwnRoute = routePostRepository.save(routeBuilder(4).driverId(RIDER).build());
        RoutePost someoneElses = routeAt(theirOwnRoute.getDepartureTime().plus(30, ChronoUnit.MINUTES), null);

        assertThatThrownBy(() -> book(someoneElses, RIDER, 1)).hasMessageContaining("a route you're driving");
    }

    @Test
    void driverCanScheduleSeveralRoutesButNotOverlappingOnes() {
        driverVerificationCacheRepository.save(new org.coryd.trip.entity.DriverVerificationCache(DRIVER, true));
        org.mockito.Mockito.when(mapsService.getRoutePreview(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                        org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble()))
                .thenReturn(new org.coryd.trip.dto.RoutePreview(9.5, 40, "40 mins", "", ""));
        Instant morning = Instant.now().plus(1, ChronoUnit.DAYS);

        RoutePost first = routeService.post(DRIVER, DRIVER, postRequest(morning));
        RoutePost evening = routeService.post(DRIVER, DRIVER, postRequest(morning.plus(9, ChronoUnit.HOURS)));

        assertThat(first.getEstimatedDurationMinutes()).isEqualTo(40);
        assertThat(evening.getId()).isNotEqualTo(first.getId());
        assertThatThrownBy(() -> routeService.post(DRIVER, DRIVER, postRequest(morning.plus(60, ChronoUnit.MINUTES))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("a route you're driving");
        assertThatThrownBy(() -> routeService.post(DRIVER, DRIVER, postRequest(Instant.now().minus(1, ChronoUnit.HOURS))))
                .hasMessageContaining("already passed");
    }

    @Test
    void aRouteCannotDepartAfterTheDriverWouldAlreadyHaveBeenSettled() {
        driverVerificationCacheRepository.save(new org.coryd.trip.entity.DriverVerificationCache(DRIVER, true));

        assertThatThrownBy(() -> routeService.post(DRIVER, DRIVER, postRequest(Instant.now().plus(4, ChronoUnit.DAYS))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("days ahead");
    }

    @Test
    void aRouteInsideTheSettlementWindowStillPosts() {
        driverVerificationCacheRepository.save(new org.coryd.trip.entity.DriverVerificationCache(DRIVER, true));

        assertThat(routeService.post(DRIVER, DRIVER, postRequest(Instant.now().plus(2, ChronoUnit.DAYS))))
                .isNotNull();
    }

    @Test
    void driverCannotPostARouteDuringARideTheyBooked() {
        driverVerificationCacheRepository.save(new org.coryd.trip.entity.DriverVerificationCache(RIDER, true));
        RoutePost ride = route(4);
        confirm(book(ride, RIDER, 1));

        assertThatThrownBy(() -> routeService.post(RIDER, RIDER, postRequest(ride.getDepartureTime().plus(20, ChronoUnit.MINUTES))))
                .hasMessageContaining("a ride you've booked");
    }

    @Test
    void rideBookedForLaterIsUpcomingNotActive() {
        Booking later = book(route(4), RIDER, 1);
        confirm(later);

        assertThat(bookingService.getActiveBookingForRider(RIDER, RIDER)).isEmpty();
        assertThat(bookingService.getUpcomingForRider(RIDER)).extracting(i -> i.bookingId()).containsExactly(later.getId());
    }

    @Test
    void rideInsideTheActiveWindowIsActive() {
        Booking later = book(route(4), RIDER, 1);
        confirm(later);
        Booking soon = book(routeAt(Instant.now().plus(20, ChronoUnit.MINUTES), null), RIDER, 1);
        confirm(soon);

        assertThat(bookingService.getActiveBookingForRider(RIDER, RIDER)).get().extracting(d -> d.bookingId()).isEqualTo(soon.getId());
        assertThat(bookingService.getUpcomingForRider(RIDER)).extracting(i -> i.bookingId()).containsExactly(later.getId());
    }

    @Test
    void startedTripIsActiveWhateverItsDepartureTime() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);
        confirm(booking);

        start(route);

        assertThat(bookingService.getActiveBookingForRider(RIDER, RIDER)).isPresent();
        assertThat(bookingService.getActiveTripForDriver(DRIVER, DRIVER)).isPresent();
        assertThat(bookingService.getUpcomingRoutesForDriver(DRIVER)).isEmpty();
    }

    @Test
    void driverRouteScheduledForLaterIsUpcomingWithItsBookings() {
        RoutePost later = route(4);
        confirm(book(later, RIDER, 2));
        RoutePost soon = routeAt(Instant.now().plus(10, ChronoUnit.MINUTES), null);

        assertThat(bookingService.getActiveTripForDriver(DRIVER, DRIVER)).get()
                .extracting(t -> t.routePostId()).isEqualTo(soon.getId());
        var upcoming = bookingService.getUpcomingRoutesForDriver(DRIVER);
        assertThat(upcoming).extracting(u -> u.routePostId()).containsExactly(later.getId());
        assertThat(upcoming.get(0).seatsBooked()).isEqualTo(2);
    }

    // ---- starting a trip -------------------------------------------------------------------------

    @Test
    void startOpensThirtyMinutesBeforeDeparture() {
        RoutePost later = route(4);
        assertThatThrownBy(() -> routeService.startTrip(later.getId(), DRIVER))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("You can start this trip from")
                .hasMessageContaining("30 minutes before departure");
        assertThat(routePostRepository.findById(later.getId()).orElseThrow().getStatus()).isEqualTo(RouteStatus.PUBLISHED);

        RoutePost soon = routeAt(Instant.now().plus(25, ChronoUnit.MINUTES), null);
        routeService.startTrip(soon.getId(), DRIVER);
        assertThat(routePostRepository.findById(soon.getId()).orElseThrow().getStatus()).isEqualTo(RouteStatus.IN_PROGRESS);
    }

    // ---- SOS -------------------------------------------------------------------------------------

    @Test
    void sosAlertsTheContactsOfWhoeverPressedIt() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);
        confirm(booking);
        org.mockito.Mockito.when(profileServiceClient.fetchEmergencyContacts(DRIVER))
                .thenReturn(List.of(new ProfileServiceClient.RiderContact("Wife", "08050000000")));

        bookingService.triggerSos(booking.getId(), DRIVER, 6.45, 3.39);

        verify(profileServiceClient).fetchEmergencyContacts(DRIVER);
        verify(profileServiceClient, never()).fetchEmergencyContacts(RIDER);
        verify(kafkaTemplate).send(eq(KafkaTopics.SOS_TRIGGERED), argThat(e -> {
            var event = (org.coryd.common.kafka.events.SosTriggeredEvent) e;
            return DRIVER.equals(event.getTriggeredBy()) && event.getContacts().size() == 1
                    && "08050000000".equals(event.getContacts().get(0).getPhone());
        }));
        assertThatThrownBy(() -> bookingService.triggerSos(booking.getId(), OTHER_RIDER, null, null)).isInstanceOf(UnauthorizedException.class);
    }

    // ---- rides nearby ----------------------------------------------------------------------------

    @Test
    void nearbyPinsAreOnlyRoutesTheRiderCanBookWithEnoughToShowAndOpenThem() {
        RoutePost bookable = routeAt(Instant.now().plus(2, ChronoUnit.HOURS), null);
        routePostRepository.save(routeBuilder(4).departureTime(Instant.now().plus(30, ChronoUnit.HOURS)).build());
        routePostRepository.save(routeBuilder(4).departureTime(Instant.now().plus(3, ChronoUnit.HOURS)).driverId(RIDER).build());
        routePostRepository.save(routeBuilder(4).departureTime(Instant.now().plus(3, ChronoUnit.HOURS)).status(RouteStatus.FULL).seatsAvailable(0).build());
        routePostRepository.save(routeBuilder(4).departureTime(Instant.now().plus(3, ChronoUnit.HOURS)).originLat(9.05).originLng(7.49).build());
        org.mockito.Mockito.when(profileServiceClient.fetchDriverSummaries(any())).thenReturn(java.util.Map.of(DRIVER,
                new ProfileServiceClient.DriverSummaryItem(DRIVER, "Tunde", "Bakare", "https://img/tunde.jpg", null)));

        var nearby = routeService.getNearbyActivity(6.45, 3.39, 5, RIDER);

        assertThat(nearby.count()).isEqualTo(1).isEqualTo(nearby.pins().size());
        var pin = nearby.pins().get(0);
        assertThat(pin.routePostId()).isEqualTo(bookable.getId());
        assertThat(pin.lat()).isEqualTo(bookable.getOriginLat());
        assertThat(pin.lng()).isEqualTo(bookable.getOriginLng());
        assertThat(pin.originLabel()).isEqualTo(bookable.getOriginLabel());
        assertThat(pin.seatsAvailable()).isEqualTo(4);
        assertThat(pin.farePerSeat()).isEqualByComparingTo(FARE);
        assertThat(pin.driverFirstName()).isEqualTo("Tunde");
    }

    @Test
    void routeDetailsSayWhetherTheRideCanBeBooked() {
        RoutePost open = route(4);
        assertThat(routeService.getRouteDetails(open.getId(), 6.45, 3.39, RIDER).bookable()).isTrue();

        assertThat(routeService.getRouteDetails(open.getId(), 6.45, 3.39, DRIVER).unavailableReason()).isEqualTo("This is your own route");

        RoutePost full = routePostRepository.save(routeBuilder(1).status(RouteStatus.FULL).seatsAvailable(0).build());
        var fullDetails = routeService.getRouteDetails(full.getId(), 6.45, 3.39, RIDER);
        assertThat(fullDetails.bookable()).isFalse();
        assertThat(fullDetails.unavailableReason()).isEqualTo("This ride is full");

        RoutePost cancelled = route(4);
        routeService.cancel(cancelled.getId(), DRIVER);
        assertThat(routeService.getRouteDetails(cancelled.getId(), 6.45, 3.39, RIDER).unavailableReason()).isEqualTo("This ride has been cancelled");

        RoutePost departed = routeAt(Instant.now().minus(10, ChronoUnit.MINUTES), null);
        assertThat(routeService.getRouteDetails(departed.getId(), 6.45, 3.39, RIDER).unavailableReason()).isEqualTo("This ride has already departed");
    }

    // ---- ops console ------------------------------------------------------------------------------

    @Test
    void opsTripsAreCountedPerTabAndTotalOnlyPaidSeats() {
        RoutePost active = route(4);
        confirm(book(active, RIDER, 2));
        Booking abandoned = book(active, OTHER_RIDER, 1);
        bookingService.expireUnpaid(reload(abandoned));
        RoutePost completed = route(4);
        Booking rode = book(completed, "rider-3", 1);
        confirm(rode);
        start(completed);
        pickUp(rode);
        bookingService.completeRoute(completed.getId(), DRIVER);
        RoutePost cancelled = route(4);
        routeService.cancel(cancelled.getId(), DRIVER);

        var counts = opsTripQueryService.counts(null);
        assertThat(counts.all()).isEqualTo(3);
        assertThat(counts.active()).isEqualTo(1);
        assertThat(counts.completed()).isEqualTo(1);
        assertThat(counts.cancelled()).isEqualTo(1);
        assertThat(opsTripQueryService.trips(null, null, firstPage()).totalElements()).as("no status is the All tab").isEqualTo(3);

        var activeTab = opsTripQueryService.trips(org.coryd.common.enums.OpsTripStatus.ACTIVE, null, firstPage());
        assertThat(activeTab.content()).singleElement().satisfies(row -> {
            assertThat(row.routePostId()).isEqualTo(active.getId());
            assertThat(row.seatsBooked()).as("the expired hold isn't a seat").isEqualTo(2);
            assertThat(row.totalFare()).isEqualByComparingTo(FARE.multiply(BigDecimal.valueOf(2)));
        });
        assertThat(opsTripQueryService.trips(org.coryd.common.enums.OpsTripStatus.CANCELLED, null, firstPage())
                .content().get(0).totalFare()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void opsTripSearchMatchesEitherEndOrTheTripId() {
        RoutePost route = route(4);

        assertThat(opsTripQueryService.counts("obalende").all()).isEqualTo(1);
        assertThat(opsTripQueryService.trips(null, route.getId().substring(0, 6), firstPage()).content())
                .extracting(r -> r.routePostId()).containsExactly(route.getId());
        assertThat(opsTripQueryService.trips(null, "ikeja", firstPage()).totalElements()).isZero();
        assertThat(opsTripQueryService.counts("ikeja").all()).isZero();
    }

    @Test
    void opsLiveMonitorSplitsStartedFromBoarding() {
        RoutePost started = route(4);
        confirm(book(started, RIDER, 1));
        start(started);
        RoutePost boarding = routeAt(Instant.now().plus(20, ChronoUnit.MINUTES), 40);
        confirm(book(boarding, OTHER_RIDER, 2));
        route(4);

        var live = opsTripQueryService.live(null);

        assertThat(live).extracting(t -> t.routePostId()).containsExactlyInAnyOrder(started.getId(), boarding.getId());
        assertThat(live).filteredOn(t -> t.routePostId().equals(started.getId())).singleElement().satisfies(t -> {
            assertThat(t.phase()).isEqualTo(org.coryd.common.enums.LiveTripPhase.ACTIVE);
            assertThat(t.seatsBooked()).isEqualTo(1);
            assertThat(t.etaAt()).isAfter(Instant.now());
        });
        assertThat(live).filteredOn(t -> t.routePostId().equals(boarding.getId())).singleElement().satisfies(t -> {
            assertThat(t.phase()).isEqualTo(org.coryd.common.enums.LiveTripPhase.BOARDING);
            assertThat(t.seatsBooked()).isEqualTo(2);
            assertThat(t.progress()).isZero();
            assertThat(t.estimatedDurationMinutes()).isEqualTo(40);
        });
        assertThat(opsTripQueryService.live("nowhere")).isEmpty();
    }

    @Test
    void opsTripDetailListsEveryPaidPassengerButNoUnpaidHolds() {
        RoutePost route = route(4);
        Booking paid = book(route, RIDER, 1);
        confirm(paid);
        Booking cancelledAfterPaying = book(route, OTHER_RIDER, 1);
        confirm(cancelledAfterPaying);
        bookingService.cancelByRider(cancelledAfterPaying.getId(), OTHER_RIDER);
        Booking neverPaid = book(route, "rider-3", 1);
        bookingService.expireUnpaid(reload(neverPaid));

        var detail = opsTripQueryService.trip(route.getId());

        assertThat(detail.passengers()).extracting(p -> p.riderId()).containsExactlyInAnyOrder(RIDER, OTHER_RIDER);
        assertThat(detail.trip().seatsBooked()).isEqualTo(1);
        assertThat(detail.location()).as("not started, so no live position").isNull();
    }

    // ---- late cancellation fee -------------------------------------------------------------------

    @Test
    void cancellingInsideTheWindowCarriesATwentyFivePercentFee() {
        RoutePost soon = routePostRepository.save(routeBuilder(4).departureTime(Instant.now().plus(1, ChronoUnit.HOURS)).build());
        Booking booking = book(soon, RIDER, 1);
        confirm(booking);

        bookingService.cancelByRider(booking.getId(), RIDER);

        verify(kafkaTemplate).send(eq(KafkaTopics.BOOKING_CANCELLED), argThat(e -> {
            BookingCancelledEvent event = (BookingCancelledEvent) e;
            return event.isFeeApplied() && event.getFeeAmount().compareTo(new BigDecimal("300.00")) == 0;
        }));
    }

    @Test
    void cancellingOutsideTheWindowIsFree() {
        RoutePost later = route(4);
        Booking booking = book(later, RIDER, 1);
        confirm(booking);

        bookingService.cancelByRider(booking.getId(), RIDER);

        verify(kafkaTemplate).send(eq(KafkaTopics.BOOKING_CANCELLED), argThat(e -> {
            BookingCancelledEvent event = (BookingCancelledEvent) e;
            return !event.isFeeApplied() && event.getFeeAmount().signum() == 0;
        }));
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private RoutePost route(int seats) {
        return routePostRepository.save(routeBuilder(seats).build());
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

    private static org.springframework.data.domain.Pageable firstPage() {
        return org.springframework.data.domain.PageRequest.of(0, 20);
    }

    private RoutePost routeAt(Instant departure, Integer durationMinutes) {
        return routePostRepository.save(routeBuilder(4).departureTime(departure).estimatedDurationMinutes(durationMinutes).build());
    }

    /** Start only opens inside the active window, so bring departure into it first. */
    private void start(RoutePost route) {
        departAt(route, Instant.now().plus(10, ChronoUnit.MINUTES));
        routeService.startTrip(route.getId(), DRIVER);
    }

    private void departAt(RoutePost route, Instant departure) {
        RoutePost r = routePostRepository.findById(route.getId()).orElseThrow();
        r.setDepartureTime(departure);
        routePostRepository.save(r);
    }

    private void postedAt(RoutePost route, Instant createdAt) {
        jdbcTemplate.update("update trip.route_posts set created_at = ? where id = ?", java.sql.Timestamp.from(createdAt), route.getId());
    }

    private static String kind(Object event) {
        return String.valueOf(((org.coryd.common.kafka.events.RouteDepartureReminderEvent) event).getKind());
    }

    private static org.coryd.trip.dto.PostRouteRequest postRequest(Instant departure) {
        return new org.coryd.trip.dto.PostRouteRequest(null, "Campos Memorial Mini Stadium", 6.45, 3.39,
                "Obalende Bridge", 6.44, 3.40, departure, 4, FARE, null, null);
    }

    private Booking book(RoutePost route, String riderId, int seats) {
        return bookingService.reserveSeats(route.getId(), riderId,
                new BookSeatsRequest(null, seats, "Pickup point", 6.45, 3.39));
    }

    private void confirm(Booking booking) {
        bookingService.confirm(booking.getId());
    }

    private void pickUp(Booking booking) {
        bookingService.markPickedUp(booking.getId(), DRIVER, reload(booking).getPickupCode());
    }

    private RoutePost startedRouteWithConfirmed(String riderId) {
        RoutePost route = route(4);
        confirm(book(route, riderId, 1));
        start(route);
        return route;
    }

    private Booking onlyBooking(RoutePost route) {
        return bookingRepository.findByRoutePostId(route.getId()).get(0);
    }

    private String wrongCode(Booking booking) {
        String code = reload(booking).getPickupCode();
        return code.equals("0000") ? "0001" : "0000";
    }

    private void setCode(Booking booking, String code) {
        Booking b = reload(booking);
        b.setPickupCode(code);
        bookingRepository.save(b);
    }

    private Booking reload(Booking booking) {
        return bookingRepository.findById(booking.getId()).orElseThrow();
    }

    private BookingStatus status(Booking booking) {
        return reload(booking).getStatus();
    }

    private int seatsLeft(RoutePost route) {
        return routePostRepository.findById(route.getId()).orElseThrow().getSeatsAvailable();
    }

    private <T> List<Future<T>> runConcurrently(int threads, Callable<T> task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                return task.call();
            }));
        }
        go.countDown();
        pool.shutdown();
        return futures;
    }

    private void await(Future<?> future) {
        try {
            future.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
