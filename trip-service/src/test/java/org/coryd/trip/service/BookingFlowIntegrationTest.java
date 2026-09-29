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
@Import({BookingService.class, RouteService.class, CancellationPolicy.class, MatchingProperties.class})
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

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;
    @MockBean ProfileServiceClient profileServiceClient;
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
        ratingRepository.deleteAll();
        historyRepository.deleteAll();
        bookingRepository.deleteAll();
        routePostRepository.deleteAll();
        clearInvocations(kafkaTemplate);
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
        routeService.startTrip(route.getId(), DRIVER);
        bookingService.markPickedUp(booking.getId(), DRIVER);

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
        routeService.startTrip(route.getId(), DRIVER);

        assertThatThrownBy(() -> bookingService.markPickedUp(booking.getId(), DRIVER)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> bookingService.completeTrip(booking.getId(), DRIVER)).isInstanceOf(BadRequestException.class);
        assertThat(status(booking)).isEqualTo(BookingStatus.CANCELLED_RIDER);
    }

    @Test
    void doubleTappedCompletePaysTheDriverOnce() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);
        confirm(booking);
        routeService.startTrip(route.getId(), DRIVER);
        bookingService.markPickedUp(booking.getId(), DRIVER);

        bookingService.completeTrip(booking.getId(), DRIVER);
        bookingService.completeTrip(booking.getId(), DRIVER);
        bookingService.completeRoute(route.getId(), DRIVER);

        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.TRIP_COMPLETED), any());
    }

    @Test
    void completedRouteCannotBeCancelled() {
        RoutePost route = route(4);
        confirm(book(route, RIDER, 1));
        routeService.startTrip(route.getId(), DRIVER);
        bookingService.markNoShow(bookingRepository.findByRoutePostId(route.getId()).get(0).getId(), DRIVER);
        bookingService.completeRoute(route.getId(), DRIVER);

        assertThatThrownBy(() -> routeService.cancel(route.getId(), DRIVER)).isInstanceOf(BadRequestException.class);
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
        routeService.startTrip(route.getId(), DRIVER);
        bookingService.markPickedUp(amaka.getId(), DRIVER);
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
        assertThatThrownBy(() -> bookingService.markPickedUp(booking.getId(), OTHER_RIDER)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void ratingIsTiedToTheBookingAndOncePerSide() {
        RoutePost route = route(4);
        Booking booking = book(route, RIDER, 1);
        confirm(booking);
        RateRequest fiveStars = new RateRequest(null, null, null, 5, null);

        assertThatThrownBy(() -> bookingService.rate(booking.getId(), RIDER, fiveStars))
                .as("not before the trip").isInstanceOf(BadRequestException.class);

        routeService.startTrip(route.getId(), DRIVER);
        bookingService.markPickedUp(booking.getId(), DRIVER);
        bookingService.completeRoute(route.getId(), DRIVER);

        assertThatThrownBy(() -> bookingService.rate(booking.getId(), OTHER_RIDER, fiveStars)).isInstanceOf(UnauthorizedException.class);
        assertThat(bookingService.rate(booking.getId(), RIDER, fiveStars).getRateeId()).isEqualTo(DRIVER);
        assertThatThrownBy(() -> bookingService.rate(booking.getId(), RIDER, fiveStars)).isInstanceOf(BadRequestException.class);
        assertThat(bookingService.rate(booking.getId(), DRIVER, fiveStars).getRateeId()).isEqualTo(RIDER);
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

    private Booking book(RoutePost route, String riderId, int seats) {
        return bookingService.reserveSeats(route.getId(), riderId,
                new BookSeatsRequest(null, seats, "Pickup point", 6.45, 3.39));
    }

    private void confirm(Booking booking) {
        bookingService.confirm(booking.getId());
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
