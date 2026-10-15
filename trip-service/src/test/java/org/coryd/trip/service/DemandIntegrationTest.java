package org.coryd.trip.service;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DriverDemandAlertEvent;
import org.coryd.common.kafka.events.NotifyMeMatchedEvent;
import org.coryd.trip.config.DemandProperties;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.dto.DemandRelevance;
import org.coryd.trip.dto.DrivingPreferences;
import org.coryd.trip.dto.NotifyMeCreateRequest;
import org.coryd.trip.dto.RouteDemandItem;
import org.coryd.trip.dto.SearchRequest;
import org.coryd.trip.entity.DriverVerificationCache;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.DemandAlertLogRepository;
import org.coryd.trip.repository.DriverDrivingPreferenceRepository;
import org.coryd.trip.repository.DriverVerificationCacheRepository;
import org.coryd.trip.repository.NotifyMeRequestRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.RouteSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** "Notify me" and rider demand against a throwaway Postgres container - never a shared or real database. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@TestPropertySource(properties = "trip.demand.max-active-notify-me=3")
@Import({NotifyMeService.class, DemandService.class, UserBlockService.class, RouteMatcher.class, org.coryd.trip.config.GeoCapabilities.class, MatchingProperties.class, DemandProperties.class})
class DemandIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final double[] IKORODU = {6.62, 3.51};
    private static final double[] VI = {6.43, 3.42};
    private static final double[] IKEJA = {6.60, 3.35};
    private static final double[] FESTAC = {6.47, 3.28};
    private static final double[] AJAH = {6.47, 3.57};
    private static final double[] LEKKI = {6.45, 3.50};

    @Autowired NotifyMeService notifyMeService;
    @Autowired DemandService demandService;
    @Autowired NotifyMeRequestRepository notifyMeRequestRepository;
    @Autowired RouteSearchRepository routeSearchRepository;
    @Autowired RoutePostRepository routePostRepository;
    @Autowired DriverDrivingPreferenceRepository preferenceRepository;
    @Autowired DriverVerificationCacheRepository verificationCacheRepository;
    @Autowired DemandAlertLogRepository demandAlertLogRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired org.coryd.trip.repository.BookingRepository bookingRepository;
    @Autowired DataSource dataSource;

    @MockBean KafkaTemplate<String, Object> kafkaTemplate;
    @MockBean org.coryd.trip.client.PaymentServiceClient paymentServiceClient;

    @BeforeEach
    void cleanSlate() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getURL())
                    .as("tests must only ever run against the Testcontainers database")
                    .isEqualTo(postgres.getJdbcUrl());
        }
        jdbcTemplate.update("delete from trip.notify_me_notifications");
        bookingRepository.deleteAll();
        notifyMeRequestRepository.deleteAll();
        routeSearchRepository.deleteAll();
        routePostRepository.deleteAll();
        preferenceRepository.deleteAll();
        verificationCacheRepository.deleteAll();
        demandAlertLogRepository.deleteAll();
        clearInvocations(kafkaTemplate);
    }

    // ---- Notify me -------------------------------------------------------------------------

    @Test
    void aOneOffRequestIsToldOnceAboutEachMatchingRide() {
        Instant tomorrow7 = tomorrowAt(7, 0);
        notifyMeService.create("rider-1", oneOff(IKORODU, VI, tomorrow7));
        RoutePost ride = ride("driver-1", IKORODU, VI, tomorrow7.plus(20, ChronoUnit.MINUTES), RouteStatus.PUBLISHED);

        notifyMeService.notifyMatching(ride);
        notifyMeService.notifyMatching(ride);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.NOTIFY_ME_MATCHED), event.capture());
        NotifyMeMatchedEvent matched = (NotifyMeMatchedEvent) event.getValue();
        assertThat(matched.getRoutePostId()).isEqualTo(ride.getId());
        assertThat(matched.getOriginLabel()).isEqualTo("Ikorodu");
        assertThat(matched.isSeatOpened()).isFalse();
    }

    @Test
    void ifTheFirstRideFillsTheRiderStillHearsAboutTheNextUntilTheyHaveASeat() {
        Instant tomorrow7 = tomorrowAt(7, 0);
        notifyMeService.create("rider-1", oneOff(IKORODU, VI, tomorrow7));
        RoutePost first = ride("driver-1", IKORODU, VI, tomorrow7, RouteStatus.FULL);
        notifyMeService.notifyMatching(first);

        notifyMeService.notifyMatching(ride("driver-2", IKORODU, VI, tomorrow7.plus(30, ChronoUnit.MINUTES), RouteStatus.PUBLISHED));
        verify(kafkaTemplate, times(2)).send(eq(KafkaTopics.NOTIFY_ME_MATCHED), any());

        RoutePost second = routePostRepository.findAll().stream().filter(r -> r.getDriverId().equals("driver-2")).findFirst().orElseThrow();
        seat("rider-1", second, BookingStatus.CONFIRMED);
        notifyMeService.notifyMatching(ride("driver-3", IKORODU, VI, tomorrow7.plus(45, ChronoUnit.MINUTES), RouteStatus.PUBLISHED));

        verify(kafkaTemplate, times(2)).send(eq(KafkaTopics.NOTIFY_ME_MATCHED), any());
        assertThat(notifyMeService.mine("rider-1")).as("done once they have a seat").isEmpty();
    }

    @Test
    void ridersToldAboutAFullRideHearOnceWhenASeatOpens() {
        Instant tomorrow7 = tomorrowAt(7, 0);
        notifyMeService.create("rider-1", oneOff(IKORODU, VI, tomorrow7));
        notifyMeService.create("rider-2", oneOff(IKORODU, VI, tomorrow7));
        RoutePost ride = ride("driver-1", IKORODU, VI, tomorrow7, RouteStatus.PUBLISHED);
        notifyMeService.notifyMatching(ride);
        seat("rider-2", ride, BookingStatus.CONFIRMED);
        notifyMeService.create("rider-3", oneOff(IKORODU, VI, tomorrow7));
        clearInvocations(kafkaTemplate);

        notifyMeService.notifySeatOpened(ride);

        var first = sentNotifyMe();
        assertThat(first).filteredOn(NotifyMeMatchedEvent::isSeatOpened).extracting(NotifyMeMatchedEvent::getRiderId)
                .as("rider-1 was told and isn't on it; rider-2 already has a seat").containsExactly("rider-1");
        assertThat(first).filteredOn(e -> !e.isSeatOpened()).extracting(NotifyMeMatchedEvent::getRiderId)
                .as("rider-3 asked after - they get the normal ride-available push").containsExactly("rider-3");

        // It fills and frees a seat again: rider-3 has now been told about it too, rider-1 never twice.
        clearInvocations(kafkaTemplate);
        notifyMeService.notifySeatOpened(ride);
        assertThat(sentNotifyMe()).extracting(e -> e.getRiderId() + ":" + e.isSeatOpened()).containsExactly("rider-3:true");

        clearInvocations(kafkaTemplate);
        notifyMeService.notifySeatOpened(ride);
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.NOTIFY_ME_MATCHED), any());
    }

    @Test
    void aRiderWaitingPartWayAlongIsToldAboutARideThatPassesThem() {
        double[] ketu = {6.57, 3.485};
        double[] obalende = {6.47, 3.435};
        Instant tomorrow7 = tomorrowAt(7, 0);
        notifyMeService.create("rider-1", oneOff(ketu, obalende, tomorrow7.plus(15, ChronoUnit.MINUTES)));
        notifyMeService.create("rider-2", oneOff(obalende, ketu, tomorrow7.plus(45, ChronoUnit.MINUTES)));
        RoutePost ride = routePostRepository.save(RoutePost.builder().driverId("driver-1").vehicleId("vehicle-1")
                .originLabel("Ikorodu").originLat(6.62).originLng(3.51).destinationLabel("VI").destinationLat(6.42).destinationLng(3.41)
                .departureTime(tomorrow7).estimatedDurationMinutes(60).seatCapacity(4).seatsAvailable(4)
                .farePerSeat(new BigDecimal("2500.00")).status(RouteStatus.PUBLISHED).build());

        notifyMeService.notifyMatching(ride);

        assertThat(sentNotifyMe()).extracting(NotifyMeMatchedEvent::getRiderId).as("not the one going the other way").containsExactly("rider-1");
    }

    @Test
    void aRepeatingRequestMatchesItsDaysAndTimeAndKeepsGoing() {
        notifyMeService.create("rider-1", weekdays(IKORODU, VI, "07:00"));
        RoutePost tuesday = ride("driver-1", IKORODU, VI, next(DayOfWeek.TUESDAY, 7, 30), RouteStatus.PUBLISHED);
        RoutePost wednesday = ride("driver-1", IKORODU, VI, next(DayOfWeek.WEDNESDAY, 6, 45), RouteStatus.PUBLISHED);
        RoutePost saturday = ride("driver-1", IKORODU, VI, next(DayOfWeek.SATURDAY, 7, 0), RouteStatus.PUBLISHED);
        RoutePost afternoon = ride("driver-1", IKORODU, VI, next(DayOfWeek.THURSDAY, 14, 0), RouteStatus.PUBLISHED);

        List.of(tuesday, wednesday, saturday, afternoon, tuesday).forEach(notifyMeService::notifyMatching);

        verify(kafkaTemplate, times(2)).send(eq(KafkaTopics.NOTIFY_ME_MATCHED), any());
        assertThat(notifyMeService.mine("rider-1")).as("still waiting for more").hasSize(1);
    }

    @Test
    void aDriverIsNeverToldAboutTheirOwnRide() {
        notifyMeService.create("driver-1", oneOff(IKORODU, VI, tomorrowAt(7, 0)));

        notifyMeService.notifyMatching(ride("driver-1", IKORODU, VI, tomorrowAt(7, 0), RouteStatus.PUBLISHED));

        verify(kafkaTemplate, never()).send(eq(KafkaTopics.NOTIFY_ME_MATCHED), any());
    }

    @Test
    void ridersSeeAndRemoveOnlyTheirOwnRequests() {
        String id = notifyMeService.create("rider-1", weekdays(IKORODU, VI, "07:00")).id();

        assertThatThrownBy(() -> notifyMeService.cancel("rider-2", id)).isInstanceOf(ResourceNotFoundException.class);
        notifyMeService.cancel("rider-1", id);
        notifyMeService.notifyMatching(ride("driver-1", IKORODU, VI, next(DayOfWeek.TUESDAY, 7, 0), RouteStatus.PUBLISHED));

        assertThat(notifyMeService.mine("rider-1")).isEmpty();
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.NOTIFY_ME_MATCHED), any());
    }

    @Test
    void requestsAreCheckedAndCapped() {
        assertThatThrownBy(() -> notifyMeService.create("rider-1", new NotifyMeCreateRequest(IKORODU[0], IKORODU[1], VI[0], VI[1],
                null, null, null, List.of(DayOfWeek.MONDAY), null, 4))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> notifyMeService.create("rider-1", oneOff(IKORODU, VI, Instant.now().minus(1, ChronoUnit.DAYS))))
                .isInstanceOf(BadRequestException.class);

        for (String time : List.of("06:00", "08:00", "10:00")) {
            notifyMeService.create("rider-1", weekdays(IKORODU, VI, time));
        }
        assertThatThrownBy(() -> notifyMeService.create("rider-1", weekdays(IKORODU, VI, "12:00")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("remove one");
    }

    @Test
    void theSameRouteAndTimeIsOnlyWaitedOnOnce() {
        Instant tomorrow7 = tomorrowAt(7, 0);
        notifyMeService.create("rider-1", oneOff(IKORODU, VI, tomorrow7));

        assertThatThrownBy(() -> notifyMeService.create("rider-1", oneOff(IKORODU, VI, tomorrow7)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already waiting");
        assertThatThrownBy(() -> notifyMeService.create("rider-1", oneOff(IKORODU, VI, tomorrow7.plus(30, ChronoUnit.MINUTES))))
                .as("half an hour later is the same request").isInstanceOf(BadRequestException.class);

        notifyMeService.create("rider-1", oneOff(IKORODU, VI, tomorrow7.plus(3, ChronoUnit.HOURS)));
        notifyMeService.create("rider-1", oneOff(VI, IKORODU, tomorrow7));
        notifyMeService.create("rider-2", oneOff(IKORODU, VI, tomorrow7));
        assertThat(notifyMeService.mine("rider-1")).as("later that day and the way back are new").hasSize(3);
    }

    @Test
    void twoOverlappingRequestsPushOncePerRide() {
        Instant tomorrow7 = tomorrowAt(7, 0);
        notifyMeService.create("rider-1", oneOff(IKORODU, VI, tomorrow7));
        notifyMeService.create("rider-1", oneOff(IKORODU, VI, tomorrow7.plus(90, ChronoUnit.MINUTES)));
        RoutePost ride = ride("driver-1", IKORODU, VI, tomorrow7.plus(45, ChronoUnit.MINUTES), RouteStatus.PUBLISHED);

        notifyMeService.notifyMatching(ride);
        assertThat(sentNotifyMe()).as("both requests match, one push").hasSize(1);

        clearInvocations(kafkaTemplate);
        notifyMeService.notifySeatOpened(ride);
        assertThat(sentNotifyMe()).extracting(NotifyMeMatchedEvent::isSeatOpened).containsExactly(true);
    }

    @Test
    void aRepeatingRequestAlreadyCoversItsDays() {
        String weekdays = notifyMeService.create("rider-1", weekdays(IKORODU, VI, "07:00")).id();

        assertThatThrownBy(() -> notifyMeService.create("rider-1", weekdays(IKORODU, VI, "07:30")))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> notifyMeService.create("rider-1", oneOff(IKORODU, VI, next(DayOfWeek.TUESDAY, 7, 0))))
                .as("a weekday one-off is already covered").isInstanceOf(BadRequestException.class);
        notifyMeService.create("rider-1", oneOff(IKORODU, VI, next(DayOfWeek.SATURDAY, 7, 0)));

        notifyMeService.cancel("rider-1", weekdays);
        notifyMeService.create("rider-1", weekdays(IKORODU, VI, "07:30"));
        assertThat(notifyMeService.mine("rider-1")).hasSize(2);
    }

    @Test
    void manyTapsAtOnceNeverGoPastTheLimit() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<?>> taps = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String time = String.format("%02d:00", 6 + 2 * i);
            taps.add(pool.submit(() -> {
                go.await();
                try {
                    notifyMeService.create("rider-1", weekdays(IKORODU, VI, time));
                } catch (BadRequestException limitReached) {
                    // expected for the taps past the limit
                }
                return null;
            }));
        }
        go.countDown();
        for (var tap : taps) {
            tap.get();
        }
        pool.shutdown();

        assertThat(notifyMeService.mine("rider-1")).as("limit is 3 in this test").hasSize(3);
    }

    // ---- what drivers see ------------------------------------------------------------------

    @Test
    void nearbySearchesBecomeOneRouteThatShowsOnlyFromThreeDifferentRiders() {
        search("rider-1", IKORODU, VI, 7, "Ikorodu", "Victoria Island");
        search("rider-1", IKORODU, VI, 7, "Ikorodu", "Victoria Island");
        search("rider-2", new double[]{IKORODU[0] + 0.005, IKORODU[1]}, VI, 7, "Ikorodu Garage", "Victoria Island");
        assertThat(demandService.demandFor("driver-1")).as("two riders could identify someone").isEmpty();

        search("rider-3", IKORODU, VI, 7, "Ikorodu", null);

        assertThat(demandService.demandFor("driver-1")).singleElement().satisfies(item -> {
            assertThat(item.riderCount()).as("rider-1 searching twice counts once").isEqualTo(3);
            assertThat(item.originLabel()).as("the name most riders used").isEqualTo("Ikorodu");
            assertThat(item.destinationLabel()).isEqualTo("Victoria Island");
            assertThat(item.windowStart()).isEqualTo("07:00");
            assertThat(item.windowEnd()).isEqualTo("07:00");
        });
    }

    @Test
    void ridersAtDifferentTimesInTheSameMorningAreOneDemandNotSeveral() {
        int[][] times = {{6, 40}, {6, 55}, {7, 10}, {7, 30}, {8, 5}};
        for (int i = 0; i < times.length; i++) {
            searchAt("rider-am-" + i, IKORODU, VI, times[i][0], times[i][1]);
        }
        for (int i = 0; i < 3; i++) {
            searchAt("rider-pm-" + i, IKORODU, VI, 18, 15 * i);
        }

        List<RouteDemandItem> list = demandService.demandFor("driver-1");

        assertThat(list).extracting(i -> i.windowStart() + "-" + i.windowEnd() + ":" + i.riderCount())
                .as("one morning window of 5 and an evening window of 3, not one group per clock hour")
                .containsExactlyInAnyOrder("06:40-08:05:5", "18:00-18:30:3");
        assertThat(list).filteredOn(i -> i.riderCount() == 5).singleElement()
                .satisfies(i -> assertThat(i.typicalTime()).isEqualTo("07:10"));
    }

    @Test
    void aSpreadOutMorningIsStillStrongEnoughToPush() {
        int[][] times = {{6, 40}, {6, 55}, {7, 10}, {7, 30}, {8, 5}};
        for (int i = 0; i < times.length; i++) {
            searchAt("rider-am-" + i, IKORODU, VI, times[i][0], times[i][1]);
        }
        driverWhoDrives("driver-fits", IKORODU, VI, 7);

        demandService.sendEveningAlerts();

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.DRIVER_DEMAND_ALERT), event.capture());
        assertThat(((DriverDemandAlertEvent) event.getValue()).getRoutes()).singleElement().satisfies(r -> {
            assertThat(r.getRiderCount()).isEqualTo(5);
            assertThat(r.getWindowStart()).isEqualTo("06:40");
            assertThat(r.getWindowEnd()).isEqualTo("08:05");
        });
    }

    @Test
    void notifyMeRequestsCountAsDemandToo() {
        search("rider-1", IKORODU, VI, 7, "Ikorodu", "Victoria Island");
        search("rider-2", IKORODU, VI, 7, "Ikorodu", "Victoria Island");
        notifyMeService.create("rider-3", oneOff(IKORODU, VI, tomorrowAt(7, 15)));

        assertThat(demandService.demandFor("driver-1")).singleElement().satisfies(item -> {
            assertThat(item.riderCount()).isEqualTo(3);
            assertThat(item.notifyMeCount()).isEqualTo(1);
        });
    }

    @Test
    void routesAreRankedByHowWellTheyFitTheDriversOwnTrips() {
        ride("driver-1", IKORODU, VI, Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
                .atZone(LAGOS).withHour(7).toInstant(), RouteStatus.COMPLETED);
        demand(IKORODU, VI, 7);
        demand(IKORODU, VI, 17);
        demand(IKORODU, IKEJA, 7);
        demand(FESTAC, VI, 7);
        demand(AJAH, LEKKI, 7);

        List<RouteDemandItem> list = demandService.demandFor("driver-1");

        assertThat(list).extracting(RouteDemandItem::relevance).containsExactly(
                DemandRelevance.MATCHES_YOUR_ROUTES, DemandRelevance.DIFFERENT_TIME, DemandRelevance.SAME_START,
                DemandRelevance.SAME_END, DemandRelevance.OTHER);
        assertThat(list.get(0).windowStart()).isEqualTo("07:00");
        assertThat(list.get(1).windowStart()).isEqualTo("17:00");
    }

    @Test
    void aNewDriversUsualRouteStandsInForTripHistory() {
        demandService.savePreferences("new-driver", new DrivingPreferences(IKORODU[0], IKORODU[1], "Ikorodu", VI[0], VI[1],
                "Victoria Island", true, false, null));
        demand(IKORODU, VI, 7);
        demand(IKORODU, VI, 18);

        List<RouteDemandItem> list = demandService.demandFor("new-driver");

        assertThat(list).extracting(RouteDemandItem::relevance)
                .containsExactly(DemandRelevance.MATCHES_YOUR_ROUTES, DemandRelevance.DIFFERENT_TIME);
        assertThat(demandService.preferences("new-driver").demandAlerts()).as("on unless turned off").isTrue();
    }

    @Test
    void ridesAlreadyPostedForADemandAreCounted() {
        demand(IKORODU, VI, 7);
        ride("driver-2", IKORODU, VI, tomorrowAt(7, 0), RouteStatus.PUBLISHED);

        assertThat(demandService.demandFor("driver-1")).singleElement()
                .satisfies(item -> assertThat(item.ridesPosted()).isEqualTo(1));
    }

    // ---- the evening push --------------------------------------------------------------------

    @Test
    void onlyDriversTheDemandFitsArePushedAndOnlyOnceADay() {
        strongDemandTomorrow(IKORODU, VI, 7, 6);
        driverWhoDrives("driver-fits", IKORODU, VI, 7);
        driverWhoDrives("driver-festac", FESTAC, VI, 7);
        driverWhoDrives("driver-opted-out", IKORODU, VI, 7);
        demandService.savePreferences("driver-opted-out", new DrivingPreferences(null, null, null, null, null, null, null, null, false));
        driverWhoDrives("driver-unverified", IKORODU, VI, 7);
        verificationCacheRepository.deleteById("driver-unverified");

        demandService.sendEveningAlerts();
        demandService.sendEveningAlerts();

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.DRIVER_DEMAND_ALERT), event.capture());
        DriverDemandAlertEvent alert = (DriverDemandAlertEvent) event.getValue();
        assertThat(alert.getDriverId()).isEqualTo("driver-fits");
        assertThat(alert.getForDate()).isEqualTo(LocalDate.now(LAGOS).plusDays(1));
        assertThat(alert.getRoutes()).singleElement().satisfies(r -> {
            assertThat(r.getRiderCount()).isEqualTo(6);
            assertThat(r.getWindowStart()).isEqualTo("07:00");
            assertThat(r.getOriginLabel()).isEqualTo("Ikorodu");
        });
    }

    @Test
    void aDriverWhoCouldntPostTheRideIsNotPushed() {
        strongDemandTomorrow(IKORODU, VI, 7, 6);
        driverWhoDrives("driver-no-bank", IKORODU, VI, 7);
        driverWhoDrives("driver-owes", IKORODU, VI, 7);
        org.mockito.Mockito.when(paymentServiceClient.postingStatus("driver-no-bank"))
                .thenReturn(new org.coryd.trip.client.PaymentServiceClient.PostingStatus(false, BigDecimal.ZERO, false));
        org.mockito.Mockito.when(paymentServiceClient.postingStatus("driver-owes"))
                .thenReturn(new org.coryd.trip.client.PaymentServiceClient.PostingStatus(true, new BigDecimal("12500"), true));

        demandService.sendEveningAlerts();

        verify(kafkaTemplate, never()).send(eq(KafkaTopics.DRIVER_DEMAND_ALERT), any());
        assertThat(demandAlertLogRepository.findAll()).as("not counted against their weekly limit either").isEmpty();
    }

    @Test
    void aVeryLongPlaceNameIsShortenedNotAnError() {
        String longName = "Ikorodu ".repeat(60);
        demandService.recordSearch("rider-1", new SearchRequest(IKORODU[0], IKORODU[1], VI[0], VI[1], tomorrowAt(7, 0), longName, null), 0);
        notifyMeService.create("rider-1", new NotifyMeCreateRequest(IKORODU[0], IKORODU[1], VI[0], VI[1], longName, null,
                tomorrowAt(7, 0), null, null, null));

        assertThat(routeSearchRepository.findAll()).singleElement()
                .satisfies(s -> assertThat(s.getOriginLabel()).hasSize(255));
        assertThat(notifyMeService.mine("rider-1")).singleElement()
                .satisfies(r -> assertThat(r.originLabel()).hasSize(255));
    }

    @Test
    void weakOrAlreadyServedDemandIsNeverPushed() {
        strongDemandTomorrow(IKORODU, VI, 7, 4);
        strongDemandTomorrow(AJAH, LEKKI, 7, 6);
        driverWhoDrives("driver-ikorodu", IKORODU, VI, 7);
        driverWhoDrives("driver-ajah", AJAH, LEKKI, 7);
        ride("someone-else", AJAH, LEKKI, tomorrowAt(7, 30), RouteStatus.PUBLISHED);

        demandService.sendEveningAlerts();

        verify(kafkaTemplate, never()).send(eq(KafkaTopics.DRIVER_DEMAND_ALERT), any());
    }

    @Test
    void aDriverIsPushedAtMostThreeTimesAWeek() {
        strongDemandTomorrow(IKORODU, VI, 7, 6);
        driverWhoDrives("driver-fits", IKORODU, VI, 7);
        LocalDate today = LocalDate.now(LAGOS);
        for (int i = 1; i <= 3; i++) {
            jdbcTemplate.update("insert into trip.demand_alert_log (id, driver_id, sent_on, route_count, created_at, updated_at) "
                    + "values (?, 'driver-fits', ?, 1, now(), now())", java.util.UUID.randomUUID().toString(), today.minusDays(i));
        }

        demandService.sendEveningAlerts();

        verify(kafkaTemplate, never()).send(eq(KafkaTopics.DRIVER_DEMAND_ALERT), any());
    }

    @Test
    void searchesAreDeletedAfterTheRetentionWindow() {
        search("rider-1", IKORODU, VI, 7, "Ikorodu", "Victoria Island");
        search("rider-2", IKORODU, VI, 7, "Ikorodu", "Victoria Island");
        jdbcTemplate.update("update trip.route_searches set created_at = now() - interval '91 days' where rider_id = 'rider-1'");

        assertThat(demandService.deleteOldSearches()).isEqualTo(1);
        assertThat(routeSearchRepository.findAll()).extracting(s -> s.getRiderId()).containsExactly("rider-2");
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void searchAt(String riderId, double[] from, double[] to, int hour, int minute) {
        demandService.recordSearch(riderId, new SearchRequest(from[0], from[1], to[0], to[1], tomorrowAt(hour, minute),
                label(from), label(to)), 0);
    }

    private void search(String riderId, double[] from, double[] to, int hour, String fromLabel, String toLabel) {
        demandService.recordSearch(riderId, new SearchRequest(from[0], from[1], to[0], to[1], tomorrowAt(hour, 0), fromLabel, toLabel), 0);
    }

    /** Three different riders - enough to show. */
    private void demand(double[] from, double[] to, int hour) {
        for (int i = 0; i < 3; i++) {
            search("rider-" + from[0] + to[0] + hour + "-" + i, from, to, hour, label(from), label(to));
        }
    }

    private void strongDemandTomorrow(double[] from, double[] to, int hour, int riders) {
        for (int i = 0; i < riders; i++) {
            search("rider-" + from[0] + "-" + i, from, to, hour, label(from), label(to));
        }
    }

    private void driverWhoDrives(String driverId, double[] from, double[] to, int hour) {
        ride(driverId, from, to, Instant.now().minus(7, ChronoUnit.DAYS).atZone(LAGOS).withHour(hour).withMinute(0).toInstant(),
                RouteStatus.COMPLETED);
        verificationCacheRepository.save(new DriverVerificationCache(driverId, true));
    }

    private RoutePost ride(String driverId, double[] from, double[] to, Instant departure, RouteStatus status) {
        return routePostRepository.save(RoutePost.builder()
                .driverId(driverId)
                .vehicleId("vehicle-1")
                .originLabel(label(from)).originLat(from[0]).originLng(from[1])
                .destinationLabel(label(to)).destinationLat(to[0]).destinationLng(to[1])
                .departureTime(departure)
                .seatCapacity(4).seatsAvailable(4)
                .farePerSeat(new BigDecimal("2500.00"))
                .status(status)
                .build());
    }

    private List<NotifyMeMatchedEvent> sentNotifyMe() {
        ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, org.mockito.Mockito.atLeast(0)).send(eq(KafkaTopics.NOTIFY_ME_MATCHED), events.capture());
        return events.getAllValues().stream().map(e -> (NotifyMeMatchedEvent) e).toList();
    }

    private void seat(String riderId, RoutePost route, BookingStatus status) {
        bookingRepository.save(org.coryd.trip.entity.Booking.builder()
                .routePostId(route.getId()).riderId(riderId).seatCount(1).fareTotal(new BigDecimal("2500.00"))
                .status(status).holdExpiresAt(Instant.now().plus(4, ChronoUnit.MINUTES)).build());
    }

    private static String label(double[] point) {
        if (point == IKORODU) return "Ikorodu";
        if (point == VI) return "Victoria Island";
        if (point == IKEJA) return "Ikeja";
        if (point == FESTAC) return "Festac";
        if (point == AJAH) return "Ajah";
        if (point == LEKKI) return "Lekki";
        return null;
    }

    private static NotifyMeCreateRequest oneOff(double[] from, double[] to, Instant when) {
        return new NotifyMeCreateRequest(from[0], from[1], to[0], to[1], label(from), label(to), when, null, null, null);
    }

    private static NotifyMeCreateRequest weekdays(double[] from, double[] to, String time) {
        return new NotifyMeCreateRequest(from[0], from[1], to[0], to[1], label(from), label(to), null,
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY), time, 4);
    }

    private static Instant tomorrowAt(int hour, int minute) {
        return LocalDate.now(LAGOS).plusDays(1).atTime(hour, minute).atZone(LAGOS).toInstant();
    }

    private static Instant next(DayOfWeek day, int hour, int minute) {
        return LocalDate.now(LAGOS).with(TemporalAdjusters.next(day)).atTime(hour, minute).atZone(LAGOS).toInstant();
    }
}
