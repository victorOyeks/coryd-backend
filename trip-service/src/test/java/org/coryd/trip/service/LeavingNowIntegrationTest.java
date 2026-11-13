package org.coryd.trip.service;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.enums.SavedPlaceType;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.DriverNearbyDemandEvent;
import org.coryd.common.kafka.events.NotifyMeMatchedEvent;
import org.coryd.common.kafka.events.RouteDepartureReminderEvent;
import org.coryd.trip.client.IdentityServiceClient;
import org.coryd.trip.client.PaymentServiceClient;
import org.coryd.trip.client.ProfileServiceClient;
import org.coryd.trip.config.DemandProperties;
import org.coryd.trip.config.MatchingProperties;
import org.coryd.trip.config.NearbyAlertProperties;
import org.coryd.trip.config.SavingsProperties;
import org.coryd.trip.config.TimeChangeProperties;
import org.coryd.trip.config.policy.CancellationPolicy;
import org.coryd.trip.config.policy.DriverVerificationPolicy;
import org.coryd.trip.dto.BookSeatsRequest;
import org.coryd.trip.dto.ChangeTripTimeRequest;
import org.coryd.trip.dto.NearbyDemandItem;
import org.coryd.trip.dto.NotifyMeCreateRequest;
import org.coryd.trip.dto.NotifyMeResponse;
import org.coryd.trip.dto.SaveHomeRequest;
import org.coryd.trip.dto.SavePlaceRequest;
import org.coryd.trip.dto.SearchRequest;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.DriverVerificationCache;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.SuspendedUser;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.DriverPresenceRepository;
import org.coryd.trip.repository.DriverVerificationCacheRepository;
import org.coryd.trip.repository.NotifyMeRequestRepository;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.RouteSearchRepository;
import org.coryd.trip.repository.RouteTemplateRepository;
import org.coryd.trip.repository.RouteTimeChangeRepository;
import org.coryd.trip.repository.SavedPlaceRepository;
import org.coryd.trip.repository.SuspendedUserRepository;
import org.coryd.trip.repository.UserBlockRepository;
import org.coryd.trip.scheduler.RouteDepartureReminderJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Leaving now" end to end - Home and recent places, waiting that ends by itself, the weekly alert at any time
 * that day, riders waiting nearby as counts, the push to drivers going that way, the regular-trip reminder and
 * the saving shown to riders. Against a throwaway Postgres built from trip-service's own migrations.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@org.springframework.test.context.TestPropertySource(properties = "identity.verification.enforcement=OFF")
@Import({BookingService.class, RouteService.class, UserBlockService.class, RouteMatcher.class, org.coryd.trip.config.GeoCapabilities.class,
        RouteTemplateService.class, org.coryd.trip.config.policy.IdentityVerificationPolicy.class, TripScheduleService.class,
        OpsTripQueryService.class, CancellationLogService.class, org.coryd.trip.config.StuckTripProperties.class, CancellationPolicy.class, MatchingProperties.class, NotifyMeService.class, DemandService.class,
        DemandProperties.class, TimeChangeProperties.class, RouteTimeChangeService.class, SavingsProperties.class,
        SavedPlaceService.class, RecentDestinationService.class, NearbyDemandService.class, NearbyAlertService.class,
        NearbyAlertProperties.class, RouteDepartureReminderJob.class, PilotMetricsService.class, OpsDriverStatsService.class, DriverStatsPublisher.class,
        OpsRiderStatsService.class, RiderStatsPublisher.class, OpsRiderTripService.class})
class LeavingNowIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    private static final String DRIVER = "driver-1";
    private static final String RIDER = "rider-1";
    private static final String RIDER_2 = "rider-2";
    private static final String RIDER_3 = "rider-3";
    private static final BigDecimal FARE = new BigDecimal("1200.00");
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    // The church car park, a few steps from it, Ajah (east), a stop on the way there, and Badagry (the other way).
    private static final double[] CHURCH = {6.6000, 3.3500};
    private static final double[] CAR_PARK = {6.6005, 3.3504};
    private static final double[] AJAH = {6.4700, 3.5800};
    private static final double[] LEKKI = {6.5300, 3.4700};
    private static final double[] BADAGRY = {6.4200, 2.8800};

    @Autowired NotifyMeService notifyMeService;
    @Autowired DemandService demandService;
    @Autowired SavedPlaceService savedPlaceService;
    @Autowired RecentDestinationService recentDestinationService;
    @Autowired NearbyDemandService nearbyDemandService;
    @Autowired NearbyAlertService nearbyAlertService;
    @Autowired NearbyAlertProperties nearbyAlertProperties;
    @Autowired SavingsProperties savingsProperties;
    @Autowired RouteDepartureReminderJob reminderJob;
    @Autowired PilotMetricsService pilotMetricsService;
    @Autowired RouteTimeChangeService routeTimeChangeService;
    @Autowired BookingService bookingService;
    @Autowired RouteService routeService;
    @Autowired UserBlockService userBlockService;
    @Autowired BookingRepository bookingRepository;
    @Autowired RoutePostRepository routePostRepository;
    @Autowired RouteSearchRepository routeSearchRepository;
    @Autowired NotifyMeRequestRepository notifyMeRequestRepository;
    @Autowired SavedPlaceRepository savedPlaceRepository;
    @Autowired DriverPresenceRepository driverPresenceRepository;
    @Autowired DriverVerificationCacheRepository driverVerificationCacheRepository;
    @Autowired SuspendedUserRepository suspendedUserRepository;
    @Autowired UserBlockRepository userBlockRepository;
    @Autowired RouteTemplateRepository routeTemplateRepository;
    @Autowired RouteTimeChangeRepository routeTimeChangeRepository;
    @Autowired JdbcTemplate jdbcTemplate;
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

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void cleanSlate() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
        }
        when(paymentServiceClient.postingStatus(anyString()))
                .thenReturn(new PaymentServiceClient.PostingStatus(true, BigDecimal.ZERO, false));
        jdbcTemplate.update("delete from trip.notify_me_notifications");
        notifyMeRequestRepository.deleteAll();
        routeSearchRepository.deleteAll();
        savedPlaceRepository.deleteAll();
        driverPresenceRepository.deleteAll();
        suspendedUserRepository.deleteAll();
        userBlockRepository.deleteAll();
        routeTemplateRepository.deleteAll();
        routeTimeChangeRepository.deleteAll();
        bookingRepository.deleteAll();
        routePostRepository.deleteAll();
        driverVerificationCacheRepository.deleteAll();
        driverVerificationCacheRepository.save(new DriverVerificationCache(DRIVER, true));
        when(driverVerificationPolicy.getEnforcement()).thenReturn(DriverVerificationPolicy.Enforcement.GRACE);
        when(driverVerificationPolicy.getPrerequisiteLevel()).thenReturn(DriverVerificationPolicy.PrerequisiteLevel.SUBMITTED);
        clearInvocations(kafkaTemplate);
    }

    @AfterEach
    void restoreSettings() {
        savingsProperties.setEnabled(true);
        nearbyAlertProperties.setEnabled(true);
    }

    // ==== Phase 2: waiting that ends by itself, and the weekly alert at any time that day ==========

    @Test
    void aOneOffWaitCanEndAfterAFewMinutesInsteadOfAnHourPastItsTime() {
        NotifyMeResponse waiting = notifyMeService.create(RIDER, oneOff(CAR_PARK, AJAH, Instant.now().plus(5, ChronoUnit.MINUTES), 45));
        assertThat(waiting.activeUntil()).isCloseTo(Instant.now().plus(45, ChronoUnit.MINUTES), within(10, ChronoUnit.SECONDS));

        NotifyMeResponse usual = notifyMeService.create(RIDER_2, oneOff(CAR_PARK, AJAH, Instant.now().plus(5, ChronoUnit.MINUTES), null));
        assertThat(usual.activeUntil()).as("unchanged without it").isCloseTo(Instant.now().plus(65, ChronoUnit.MINUTES), within(10, ChronoUnit.SECONDS));
        assertThat(usual.anyTimeOfDay()).isFalse();
    }

    @Test
    void expiresInMinutesIsOnlyForOneOffsAndAnyTimeOfDayOnlyForRepeating() {
        assertThatThrownBy(() -> notifyMeService.create(RIDER, sundays(CHURCH, AJAH, "13:30", false, 45)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("one-off");
        assertThatThrownBy(() -> notifyMeService.create(RIDER, new NotifyMeCreateRequest(CHURCH[0], CHURCH[1], AJAH[0], AJAH[1], "Church X", "Ajah",
                Instant.now().plus(10, ChronoUnit.MINUTES), null, null, null, null, true)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("repeating");
        assertThat(validator.validate(oneOff(CAR_PARK, AJAH, Instant.now(), 4))).isNotEmpty();
        assertThat(validator.validate(oneOff(CAR_PARK, AJAH, Instant.now(), 721))).isNotEmpty();
        assertThat(validator.validate(oneOff(CAR_PARK, AJAH, Instant.now(), 45))).isEmpty();
    }

    @Test
    void aWeeklyAlertForAnyTimeOfDayHearsOfATripLateThatDayAndAUsualOneDoesNot() {
        notifyMeService.create(RIDER, sundays(CHURCH, AJAH, "13:30", true, null));
        notifyMeService.create(RIDER_2, sundays(CHURCH, AJAH, "13:30", false, null));
        RoutePost lateService = routePostRepository.save(route(CHURCH, AJAH).departureTime(nextSundayAt(18, 0)).build());

        notifyMeService.notifyMatching(lateService);

        verify(kafkaTemplate).send(eq(KafkaTopics.NOTIFY_ME_MATCHED), argThat(e -> e instanceof NotifyMeMatchedEvent m && m.getRiderId().equals(RIDER)));
        verify(kafkaTemplate, never()).send(eq(KafkaTopics.NOTIFY_ME_MATCHED), argThat(e -> e instanceof NotifyMeMatchedEvent m && m.getRiderId().equals(RIDER_2)));
    }

    @Test
    void aSecondWeeklyAlertForTheSameRouteAndDayIsADuplicateWhenEitherIsAnyTime() {
        notifyMeService.create(RIDER, sundays(CHURCH, AJAH, "13:30", true, null));
        assertThatThrownBy(() -> notifyMeService.create(RIDER, sundays(CHURCH, AJAH, "19:00", false, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("already waiting");
    }

    // ==== Phase 2: Home and recent places ==========================================================

    @Test
    void homeIsOnePlacePerUserAndSettingItAgainReplacesIt() {
        assertThat(savedPlaceService.getHome(DRIVER)).isEmpty();

        savedPlaceService.saveHome(DRIVER, new SaveHomeRequest("Ajah", null, AJAH[0], AJAH[1]));
        savedPlaceService.saveHome(DRIVER, new SaveHomeRequest(" Lekki Phase 1 ", "12 Admiralty Way", LEKKI[0], LEKKI[1]));
        savedPlaceService.addPlace(DRIVER, new SavePlaceRequest("Office", "1 Broad St", 6.45, 3.39, null));

        assertThat(savedPlaceService.getPlaces(DRIVER)).filteredOn(p -> p.getType() == SavedPlaceType.HOME).singleElement()
                .satisfies(h -> {
                    assertThat(h.getLabel()).isEqualTo("Lekki Phase 1");
                    assertThat(h.getFormattedAddress()).isEqualTo("12 Admiralty Way");
                    assertThat(h.getLat()).isEqualTo(LEKKI[0]);
                });
        assertThat(savedPlaceService.getPlaces(DRIVER)).hasSize(2);
    }

    @Test
    void homeSentThroughTheOrdinaryAddPlaceAlsoReplacesTheOldHome() {
        savedPlaceService.saveHome(RIDER, new SaveHomeRequest("Ajah", null, AJAH[0], AJAH[1]));
        savedPlaceService.addPlace(RIDER, new SavePlaceRequest("Lekki", "Lekki", LEKKI[0], LEKKI[1], SavedPlaceType.HOME));

        assertThat(savedPlaceService.getHome(RIDER)).get().satisfies(h -> {
            assertThat(h.getLabel()).isEqualTo("Lekki");
            assertThat(h.getFormattedAddress()).as("the label when no address is given").isEqualTo("Lekki");
        });
        assertThat(savedPlaceService.getHome(DRIVER)).as("another user's Home is untouched").isEmpty();
    }

    @Test
    void theDatabaseItselfRefusesASecondHome() {
        savedPlaceService.saveHome(RIDER, new SaveHomeRequest("Ajah", null, AJAH[0], AJAH[1]));
        assertThatThrownBy(() -> jdbcTemplate.update("insert into trip.saved_places (id, user_id, label, formatted_address, lat, lng, type, created_at, updated_at) "
                + "values ('x', ?, 'Two', 'Two', 1, 1, 'HOME', now(), now())", RIDER))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void recentDestinationsMixRiddenAndDrivenPlacesNewestFirstEachOnceWithoutCancelledTrips() {
        RoutePost rodeToAjah = routePostRepository.save(route(CHURCH, AJAH).driverId("someone-else").build());
        confirmed(rodeToAjah, DRIVER);
        createdMinutesAgo("trip.bookings", bookingRepository.findByRoutePostId(rodeToAjah.getId()).get(0).getId(), 30);
        RoutePost droveToLekki = routePostRepository.save(route(CHURCH, LEKKI).departureTime(Instant.now().plus(2, ChronoUnit.DAYS)).build());
        createdMinutesAgo("trip.route_posts", droveToLekki.getId(), 10);
        RoutePost droveNearAjahAgain = routePostRepository.save(route(CHURCH, new double[]{AJAH[0] + 0.001, AJAH[1]})
                .departureTime(Instant.now().plus(3, ChronoUnit.HOURS)).build());
        createdMinutesAgo("trip.route_posts", droveNearAjahAgain.getId(), 60);
        routePostRepository.save(route(CHURCH, BADAGRY).departureTime(Instant.now().plus(5, ChronoUnit.HOURS)).status(RouteStatus.CANCELLED).build());

        var recent = recentDestinationService.recent(DRIVER, 5);

        assertThat(recent).extracting(r -> r.label()).containsExactly("Lekki", "Ajah");
        assertThat(recentDestinationService.recent(DRIVER, 1)).hasSize(1);
        assertThat(recentDestinationService.recent(DRIVER, 0)).as("at least one").hasSize(1);
        assertThat(recentDestinationService.recent("nobody", 5)).isEmpty();
    }

    @Test
    void theCarsColourIsOnSearchResultsAndBookings() {
        when(profileServiceClient.fetchVehicleSummaries(anyList())).thenReturn(Map.of("vehicle-1",
                new ProfileServiceClient.VehicleSummaryItem("vehicle-1", "Toyota", "Corolla", "ABC123XY", true, 4, null, 2015, "Silver")));
        RoutePost route = routePostRepository.save(route(CHURCH, AJAH).build());
        Booking booking = confirmed(route, RIDER);

        assertThat(routeService.searchAsRider(new SearchRequest(CHURCH[0], CHURCH[1], AJAH[0], AJAH[1], route.getDepartureTime(), null, null), RIDER_2))
                .singleElement().satisfies(r -> assertThat(r.vehicleColour()).isEqualTo("Silver"));
        assertThat(bookingService.getBookingDetails(booking.getId(), RIDER).vehicleColour()).isEqualTo("Silver");
    }

    // ==== Phase 3: riders waiting nearby, as counts ===============================================

    @Test
    void ridersWhoJustSearchedFromTheCarParkAreCountedByWhereTheyreGoing() {
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_2, CHURCH, AJAH, "Ajah");
        searchedNow(RIDER_3, CHURCH, BADAGRY, "Badagry");
        searchedNow("rider-4", CHURCH, BADAGRY, "Badagry");
        searchedNow("rider-5", CHURCH, BADAGRY, null);

        List<NearbyDemandItem> nearby = nearbyDemandService.nearby(DRIVER, CHURCH[0], CHURCH[1], null, null, null, null);

        assertThat(nearby).extracting(NearbyDemandItem::areaLabel, NearbyDemandItem::waitingCount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Badagry", 3), org.assertj.core.groups.Tuple.tuple("Ajah", 2));
        assertThat(nearby.get(1).destinationLat()).as("the middle of the area, never a rider's point").isNotEqualTo(AJAH[0]);
    }

    @Test
    void aSingleWaitingRiderIsNeverShown() {
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        assertThat(nearbyDemandService.nearby(DRIVER, CHURCH[0], CHURCH[1], null, null, null, null)).isEmpty();
    }

    @Test
    void theCallerAndAnyoneKeptApartFromThemAreNeverCounted() {
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_2, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_3, CAR_PARK, AJAH, "Ajah");

        assertThat(nearbyDemandService.nearby(RIDER, CHURCH[0], CHURCH[1], null, null, null, null))
                .singleElement().extracting(NearbyDemandItem::waitingCount).isEqualTo(2);
        userBlockService.block(DRIVER, RIDER_2);
        assertThat(nearbyDemandService.nearby(DRIVER, CHURCH[0], CHURCH[1], null, null, null, null))
                .singleElement().extracting(NearbyDemandItem::waitingCount).isEqualTo(2);
    }

    @Test
    void aRiderWhoAlreadyHasASeatIsNotWaiting() {
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_2, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_3, CAR_PARK, AJAH, "Ajah");
        confirmed(routePostRepository.save(route(CHURCH, AJAH).driverId("other-driver")
                .departureTime(Instant.now().plus(20, ChronoUnit.MINUTES)).build()), RIDER_3);

        assertThat(nearbyDemandService.nearby(DRIVER, CHURCH[0], CHURCH[1], null, null, null, null))
                .singleElement().extracting(NearbyDemandItem::waitingCount).isEqualTo(2);
    }

    @Test
    void searchesForLaterOrFromAWhileAgoAndRidersFurtherAwayDontCount() {
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        demandService.recordSearch(RIDER_2, new SearchRequest(CAR_PARK[0], CAR_PARK[1], AJAH[0], AJAH[1],
                Instant.now().plus(1, ChronoUnit.DAYS), null, "Ajah"), 0);
        searchedNow(RIDER_3, CAR_PARK, AJAH, "Ajah");
        jdbcTemplate.update("update trip.route_searches set created_at = created_at - interval '16 minutes' where rider_id = ?", RIDER_3);
        searchedNow("rider-4", new double[]{CHURCH[0] + 0.01, CHURCH[1]}, AJAH, "Ajah");

        assertThat(nearbyDemandService.nearby(DRIVER, CHURCH[0], CHURCH[1], null, null, null, null)).isEmpty();
        assertThat(nearbyDemandService.nearby(DRIVER, CHURCH[0], CHURCH[1], 2000, null, null, null))
                .as("a wider radius takes in the rider 1.1 km away").singleElement().extracting(NearbyDemandItem::waitingCount).isEqualTo(2);
        assertThat(nearbyDemandService.nearby(DRIVER, CHURCH[0], CHURCH[1], 999_999, null, null, null))
                .as("capped at 2 km").singleElement().extracting(NearbyDemandItem::waitingCount).isEqualTo(2);
    }

    @Test
    void oneOffWaitsCountAndARiderWhoBothSearchedAndWaitedCountsOnce() {
        notifyMeService.create(RIDER, oneOff(CAR_PARK, AJAH, Instant.now().plus(5, ChronoUnit.MINUTES), 45));
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        notifyMeService.create(RIDER_2, oneOff(CAR_PARK, AJAH, Instant.now().plus(5, ChronoUnit.MINUTES), 45));
        NotifyMeResponse cancelled = notifyMeService.create(RIDER_3, oneOff(CAR_PARK, AJAH, Instant.now().plus(5, ChronoUnit.MINUTES), 45));
        notifyMeService.cancel(RIDER_3, cancelled.id());

        assertThat(nearbyDemandService.nearby(DRIVER, CHURCH[0], CHURCH[1], null, null, null, null))
                .singleElement().extracting(NearbyDemandItem::waitingCount).isEqualTo(2);
    }

    @Test
    void goingMyWayCountsRidersOnTheWayToTheDriversDestinationNotTheOtherWay() {
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_2, CAR_PARK, LEKKI, "Lekki");
        searchedNow(RIDER_3, CAR_PARK, BADAGRY, "Badagry");
        searchedNow("rider-4", CAR_PARK, BADAGRY, "Badagry");

        List<NearbyDemandItem> myWay = nearbyDemandService.nearby(DRIVER, CHURCH[0], CHURCH[1], null, null, AJAH[0], AJAH[1]);

        assertThat(myWay.stream().mapToInt(NearbyDemandItem::waitingCount).sum()).as("Ajah, and Lekki on the way").isZero();
        // Each area alone is a single rider - hidden. Two to Lekki makes it show.
        searchedNow("rider-5", CAR_PARK, LEKKI, "Lekki");
        assertThat(nearbyDemandService.nearby(DRIVER, CHURCH[0], CHURCH[1], null, null, AJAH[0], AJAH[1]))
                .singleElement().satisfies(i -> {
                    assertThat(i.areaLabel()).isEqualTo("Lekki");
                    assertThat(i.waitingCount()).isEqualTo(2);
                });
    }

    @Test
    void aDriversOwnTripCountsOnlyTheRidersItCanCarry() {
        RoutePost mine = routePostRepository.save(route(CHURCH, AJAH).departureTime(Instant.now().plus(5, ChronoUnit.MINUTES)).build());
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_2, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_3, CAR_PARK, BADAGRY, "Badagry");
        searchedNow("rider-4", CAR_PARK, BADAGRY, "Badagry");

        assertThat(nearbyDemandService.nearby(DRIVER, CHURCH[0], CHURCH[1], null, mine.getId(), null, null))
                .singleElement().satisfies(i -> {
                    assertThat(i.areaLabel()).isEqualTo("Ajah");
                    assertThat(i.waitingCount()).isEqualTo(2);
                });
        assertThatThrownBy(() -> nearbyDemandService.nearby(RIDER, CHURCH[0], CHURCH[1], null, mine.getId(), null, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void theFiltersAreCheckedTogether() {
        assertThatThrownBy(() -> nearbyDemandService.nearby(DRIVER, CHURCH[0], CHURCH[1], null, null, AJAH[0], null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> nearbyDemandService.nearby(DRIVER, CHURCH[0], CHURCH[1], null, "route", AJAH[0], AJAH[1]))
                .isInstanceOf(BadRequestException.class);
    }

    // ==== Phase 4: telling a driver near waiting riders who are going their way ===================

    @Test
    void aDriverWhoOpenedTheAppNearRidersGoingTowardsTheirHomeIsToldOnce() {
        driverHomeAtAjah();
        nearbyAlertService.recordLocation(DRIVER, CHURCH[0], CHURCH[1]);
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_2, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_3, CAR_PARK, LEKKI, "Lekki");

        assertThat(nearbyAlertService.sendAlerts()).isEqualTo(1);
        assertThat(nearbyAlertService.sendAlerts()).as("not again within the cooldown").isZero();

        verify(kafkaTemplate, times(1)).send(eq(KafkaTopics.DRIVER_NEARBY_DEMAND), argThat(e -> e instanceof DriverNearbyDemandEvent d
                && d.getDriverId().equals(DRIVER) && d.getRiderCount() == 3 && "Ajah".equals(d.getDestinationLabel())));
    }

    @Test
    void afterTheCooldownTheDriverCanBeToldAgainButNotMoreThanTheDailyLimit() {
        driverHomeAtAjah();
        nearbyAlertService.recordLocation(DRIVER, CHURCH[0], CHURCH[1]);
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_2, CAR_PARK, AJAH, "Ajah");

        int sent = 0;
        for (int i = 0; i < nearbyAlertProperties.getMaxPerDay() + 2; i++) {
            sent += nearbyAlertService.sendAlerts();
            jdbcTemplate.update("update trip.driver_presence set last_nearby_alert_at = last_nearby_alert_at - interval '31 minutes' where driver_id = ?", DRIVER);
        }

        assertThat(sent).isEqualTo(nearbyAlertProperties.getMaxPerDay());
    }

    @Test
    void nobodyIsToldWithoutAFreshLocationOrOnceItsForgotten() {
        driverHomeAtAjah();
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_2, CAR_PARK, AJAH, "Ajah");
        assertThat(nearbyAlertService.sendAlerts()).as("never opened the app").isZero();

        nearbyAlertService.recordLocation(DRIVER, CHURCH[0], CHURCH[1]);
        jdbcTemplate.update("update trip.driver_presence set seen_at = seen_at - interval '31 minutes' where driver_id = ?", DRIVER);
        assertThat(nearbyAlertService.sendAlerts()).as("location older than 30 minutes").isZero();

        nearbyAlertService.recordLocation(DRIVER, CHURCH[0], CHURCH[1]);
        nearbyAlertService.forgetLocation(DRIVER);
        assertThat(nearbyAlertService.sendAlerts()).as("signed out").isZero();
    }

    @Test
    void staleLocationsAreClearedSoThereIsNoTrail() {
        nearbyAlertService.recordLocation(DRIVER, CHURCH[0], CHURCH[1]);
        nearbyAlertService.recordLocation(DRIVER, CAR_PARK[0], CAR_PARK[1]);
        assertThat(driverPresenceRepository.findAll()).as("one row, overwritten").singleElement()
                .satisfies(p -> assertThat(p.getLat()).isEqualTo(CAR_PARK[0]));
        jdbcTemplate.update("update trip.driver_presence set seen_at = seen_at - interval '31 minutes' where driver_id = ?", DRIVER);

        nearbyAlertService.clearStale();

        assertThat(driverPresenceRepository.findAll()).as("never alerted, so removed outright").isEmpty();
    }

    @Test
    void aDriverIsOnlyToldAboutTheirOwnWay() {
        driverHomeAtAjah();
        nearbyAlertService.recordLocation(DRIVER, CHURCH[0], CHURCH[1]);
        searchedNow(RIDER, CAR_PARK, BADAGRY, "Badagry");
        searchedNow(RIDER_2, CAR_PARK, BADAGRY, "Badagry");

        assertThat(nearbyAlertService.sendAlerts()).isZero();
    }

    @Test
    void aDriverWithNowhereTheyGoIsNotTold() {
        nearbyAlertService.recordLocation(DRIVER, CHURCH[0], CHURCH[1]);
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_2, CAR_PARK, AJAH, "Ajah");

        assertThat(nearbyAlertService.sendAlerts()).isZero();
    }

    @Test
    void aTripTheyDroveLatelyCountsAsWhereTheyGo() {
        RoutePost lastWeek = routePostRepository.save(route(CHURCH, AJAH).departureTime(Instant.now().minus(7, ChronoUnit.DAYS))
                .status(RouteStatus.COMPLETED).build());
        assertThat(lastWeek.getId()).isNotNull();
        nearbyAlertService.recordLocation(DRIVER, CHURCH[0], CHURCH[1]);
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_2, CAR_PARK, AJAH, "Ajah");

        assertThat(nearbyAlertService.sendAlerts()).isEqualTo(1);
    }

    @Test
    void aDriverAlreadyLeavingSuspendedOrUnableToPostIsNotTold() {
        driverHomeAtAjah();
        nearbyAlertService.recordLocation(DRIVER, CHURCH[0], CHURCH[1]);
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_2, CAR_PARK, AJAH, "Ajah");

        RoutePost leaving = routePostRepository.save(route(CHURCH, AJAH).departureTime(Instant.now().plus(5, ChronoUnit.MINUTES)).build());
        assertThat(nearbyAlertService.sendAlerts()).as("already posted a trip leaving now").isZero();
        routePostRepository.delete(leaving);

        suspendedUserRepository.save(new SuspendedUser(DRIVER, "Test", Instant.now()));
        assertThat(nearbyAlertService.sendAlerts()).as("suspended").isZero();
        suspendedUserRepository.deleteAll();

        when(paymentServiceClient.postingStatus(DRIVER)).thenReturn(new PaymentServiceClient.PostingStatus(false, BigDecimal.ZERO, false));
        assertThat(nearbyAlertService.sendAlerts()).as("no payout account - can't post").isZero();
        when(paymentServiceClient.postingStatus(DRIVER)).thenReturn(new PaymentServiceClient.PostingStatus(true, BigDecimal.ZERO, false));

        nearbyAlertProperties.setEnabled(false);
        assertThat(nearbyAlertService.sendAlerts()).as("switched off").isZero();
        nearbyAlertProperties.setEnabled(true);

        assertThat(nearbyAlertService.sendAlerts()).as("and once all is well, they are told").isEqualTo(1);
    }

    @Test
    void ridersKeptApartFromTheDriverDontCountTowardsTheirPush() {
        driverHomeAtAjah();
        nearbyAlertService.recordLocation(DRIVER, CHURCH[0], CHURCH[1]);
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_2, CAR_PARK, AJAH, "Ajah");
        userBlockService.block(RIDER_2, DRIVER);

        assertThat(nearbyAlertService.sendAlerts()).isZero();
    }

    // ==== Phase 5: the regular-trip reminder carries who's waiting ===============================

    @Test
    void theDriversGetReadyReminderSaysHowManyNearTheStartAreGoingTheirWay() {
        RoutePost route = routePostRepository.save(route(CHURCH, AJAH).departureTime(Instant.now().plus(25, ChronoUnit.MINUTES)).build());
        createdMinutesAgo("trip.route_posts", route.getId(), 24 * 60);
        searchedNow(RIDER, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_2, CAR_PARK, AJAH, "Ajah");
        searchedNow(RIDER_3, CAR_PARK, BADAGRY, "Badagry");

        reminderJob.sendUpcomingDepartureReminders();

        verify(kafkaTemplate).send(eq(KafkaTopics.ROUTE_DEPARTURE_REMINDER), argThat(e -> e instanceof RouteDepartureReminderEvent r
                && RouteDepartureReminderJob.DRIVER_GET_READY.equals(r.getKind()) && Integer.valueOf(2).equals(r.getWaitingNearby())));
    }

    @Test
    void runningLateFromTheReminderKeepsTheTripAndRemindsAgainForTheNewTime() {
        RoutePost route = routePostRepository.save(route(CHURCH, AJAH).departureTime(Instant.now().plus(25, ChronoUnit.MINUTES)).build());
        createdMinutesAgo("trip.route_posts", route.getId(), 24 * 60);
        confirmed(route, RIDER);
        reminderJob.sendUpcomingDepartureReminders();
        clearInvocations(kafkaTemplate);

        routeTimeChangeService.change(route.getId(), DRIVER, new ChangeTripTimeRequest(null, 3));
        reminderJob.sendUpcomingDepartureReminders();

        verify(kafkaTemplate).send(eq(KafkaTopics.ROUTE_DEPARTURE_REMINDER), argThat(e -> e instanceof RouteDepartureReminderEvent r
                && RouteDepartureReminderJob.DRIVER_GET_READY.equals(r.getKind())));
    }

    // ==== Phase 6: showing it works ==============================================================

    @Test
    void aRiderSeesRoughlyWhatASoloCabWouldHaveCostAndWhatTheySaved() {
        RoutePost route = routePostRepository.save(route(CHURCH, AJAH).build());
        Booking booking = confirmed(route, RIDER);

        var details = bookingService.getBookingDetails(booking.getId(), RIDER);

        // ~29.2 km straight, x1.3 by road = ~38 km: 700 + 200 x 38 = ~8,300.
        assertThat(details.soloCabEstimate()).isBetween(new BigDecimal("7500"), new BigDecimal("9000"));
        assertThat(details.soloCabEstimate().remainder(new BigDecimal("100"))).isEqualByComparingTo("0");
        assertThat(details.estimatedSaving()).isEqualByComparingTo(details.soloCabEstimate().subtract(FARE));

        savingsProperties.setEnabled(false);
        var off = bookingService.getBookingDetails(booking.getId(), RIDER);
        assertThat(off.soloCabEstimate()).isNull();
        assertThat(off.estimatedSaving()).isNull();
    }

    @Test
    void theSavingIsNeverBelowZero() {
        RoutePost shortHop = routePostRepository.save(route(CHURCH, CAR_PARK).farePerSeat(new BigDecimal("5000.00")).build());
        Booking booking = confirmed(shortHop, RIDER);

        assertThat(bookingService.getBookingDetails(booking.getId(), RIDER).estimatedSaving()).isEqualByComparingTo("0");
    }

    @Test
    void pilotMetricsCountLeavingNowTripsWaitsThatFoundASeatAndWhereNoCarCame() {
        RoutePost leftNow = routePostRepository.save(route(CHURCH, AJAH).build());
        confirmed(leftNow, RIDER);
        RoutePost ran = routePostRepository.findById(leftNow.getId()).orElseThrow();
        ran.setDepartureTime(Instant.now().minus(1, ChronoUnit.HOURS));
        ran.setStatus(RouteStatus.COMPLETED);
        routePostRepository.save(ran);
        createdMinutesAgo("trip.route_posts", leftNow.getId(), 65);
        RoutePost planned = routePostRepository.save(route(CHURCH, LEKKI).departureTime(Instant.now().minus(2, ChronoUnit.HOURS))
                .status(RouteStatus.COMPLETED).build());
        createdMinutesAgo("trip.route_posts", planned.getId(), 60 * 24);

        NotifyMeResponse found = notifyMeService.create(RIDER, oneOff(CAR_PARK, AJAH, Instant.now().plus(5, ChronoUnit.MINUTES), 45));
        jdbcTemplate.update("update trip.notify_me_requests set fulfilled = true where id = ?", found.id());
        NotifyMeResponse unmet = notifyMeService.create(RIDER_2, oneOff(CAR_PARK, AJAH, Instant.now().plus(5, ChronoUnit.MINUTES), 45));
        jdbcTemplate.update("update trip.notify_me_requests set expires_at = created_at - interval '1 minute' where id = ?", unmet.id());
        notifyMeService.create(RIDER_3, oneOff(CAR_PARK, AJAH, Instant.now().plus(1, ChronoUnit.DAYS), null));

        var leavingNow = pilotMetricsService.metrics(7).leavingNow();

        assertThat(leavingNow.tripsPostedLeavingNow()).isEqualTo(1);
        assertThat(leavingNow.seatsBookedOnThem()).isEqualTo(1);
        assertThat(leavingNow.ridersWaited()).as("tomorrow's request is planning, not waiting").isEqualTo(2);
        assertThat(leavingNow.ridersFoundASeat()).isEqualTo(1);
        assertThat(leavingNow.waitFoundSeatRate()).isEqualTo(0.5);
        assertThat(leavingNow.unmetAreas()).singleElement().satisfies(a -> {
            assertThat(a.destinationLabel()).isEqualTo("ajah");
            assertThat(a.riders()).isEqualTo(1);
        });
    }

    @Test
    void pilotMetricsWorkOutSearchesBookingsFillRepeatRidersAndTopRoutesInTheDatabase() {
        demandService.recordSearch(RIDER, new SearchRequest(CHURCH[0], CHURCH[1], AJAH[0], AJAH[1], Instant.now(), "Church X", "Ajah"), 1);
        demandService.recordSearch(RIDER, new SearchRequest(CHURCH[0], CHURCH[1], AJAH[0], AJAH[1], Instant.now(), " church x ", "AJAH"), 0);
        demandService.recordSearch(RIDER_2, new SearchRequest(CHURCH[0], CHURCH[1], LEKKI[0], LEKKI[1], Instant.now(), "Church X", "Lekki"), 0);
        RoutePost ran = routePostRepository.save(route(CHURCH, AJAH).build());
        confirmed(ran, RIDER);
        confirmed(ran, RIDER_3);
        RoutePost moved = routePostRepository.findById(ran.getId()).orElseThrow();
        moved.setDepartureTime(Instant.now().minus(2, ChronoUnit.DAYS));
        moved.setStatus(RouteStatus.COMPLETED);
        routePostRepository.save(moved);
        jdbcTemplate.update("update trip.bookings set status = 'COMPLETED' where route_post_id = ?", ran.getId());
        RoutePost lastWeek = routePostRepository.save(route(CHURCH, AJAH).departureTime(Instant.now().minus(10, ChronoUnit.DAYS))
                .status(RouteStatus.COMPLETED).build());
        jdbcTemplate.update("insert into trip.bookings (id, route_post_id, rider_id, seat_count, fare_total, status, hold_expires_at, "
                + "pickup_code_attempts, departure_shift_minutes, created_at, updated_at) "
                + "select 'b-last-1', ?, ?, 1, 1200, 'COMPLETED', created_at, 0, 0, created_at, created_at from trip.route_posts where id = ?",
                lastWeek.getId(), RIDER, lastWeek.getId());
        jdbcTemplate.update("insert into trip.bookings (id, route_post_id, rider_id, seat_count, fare_total, status, hold_expires_at, "
                + "pickup_code_attempts, departure_shift_minutes, created_at, updated_at) "
                + "select 'b-last-2', ?, ?, 1, 1200, 'COMPLETED', created_at, 0, 0, created_at, created_at from trip.route_posts where id = ?",
                lastWeek.getId(), RIDER_2, lastWeek.getId());

        var metrics = pilotMetricsService.metrics(7);

        assertThat(metrics.totals().searches()).isEqualTo(3);
        assertThat(metrics.totals().searchesWithResults()).isEqualTo(1);
        assertThat(metrics.totals().ridersSearching()).isEqualTo(2);
        assertThat(metrics.totals().ridersBooked()).as("rider-3 booked without searching").isEqualTo(1);
        assertThat(metrics.totals().routesPosted()).isEqualTo(1);
        assertThat(metrics.totals().seatsOffered()).isEqualTo(4);
        assertThat(metrics.totals().seatsBooked()).isEqualTo(2);
        assertThat(metrics.totals().fillRate()).isEqualTo(0.5);
        assertThat(metrics.weekTwoRepeatRate()).as("rider-1 came back, rider-2 didn't").isEqualTo(0.5);
        assertThat(metrics.topSearchedRoutes()).hasSize(2);
        assertThat(metrics.topSearchedRoutes().get(0)).satisfies(r -> {
            assertThat(r.searches()).as("the same place typed two ways").isEqualTo(2);
            assertThat(r.riders()).isEqualTo(1);
            assertThat(r.searchesWithNoResults()).isEqualTo(1);
        });
    }

    @Test
    void pilotMetricsCountTimeChangesAndTheLastMinuteOnes() {
        RoutePost route = routePostRepository.save(route(CHURCH, AJAH).departureTime(Instant.now().plus(20, ChronoUnit.MINUTES)).build());
        confirmed(route, RIDER);
        routeTimeChangeService.change(route.getId(), DRIVER, new ChangeTripTimeRequest(null, 10));
        RoutePost later = routePostRepository.save(route(CHURCH, LEKKI).departureTime(Instant.now().plus(1, ChronoUnit.DAYS)).build());
        routeTimeChangeService.change(later.getId(), DRIVER, new ChangeTripTimeRequest(null, 10));

        var leavingNow = pilotMetricsService.metrics(7).leavingNow();

        assertThat(leavingNow.timeChanges()).isEqualTo(2);
        assertThat(leavingNow.lastMinuteTimeChanges()).isEqualTo(1);
    }

    // ==== helpers ================================================================================

    private void searchedNow(String riderId, double[] from, double[] to, String destinationLabel) {
        demandService.recordSearch(riderId, new SearchRequest(from[0], from[1], to[0], to[1], Instant.now(), "Church X", destinationLabel), 0);
    }

    private void driverHomeAtAjah() {
        savedPlaceService.saveHome(DRIVER, new SaveHomeRequest("Ajah", null, AJAH[0], AJAH[1]));
    }

    private static NotifyMeCreateRequest oneOff(double[] from, double[] to, Instant when, Integer expiresInMinutes) {
        return new NotifyMeCreateRequest(from[0], from[1], to[0], to[1], "Church X", "Ajah", when, null, null, null, expiresInMinutes, null);
    }

    private static NotifyMeCreateRequest sundays(double[] from, double[] to, String time, boolean anyTime, Integer expiresInMinutes) {
        return new NotifyMeCreateRequest(from[0], from[1], to[0], to[1], "Church X", "Ajah", null, List.of(DayOfWeek.SUNDAY), time, 4,
                expiresInMinutes, anyTime);
    }

    private static Instant nextSundayAt(int hour, int minute) {
        LocalDate sunday = LocalDate.now(LAGOS).with(TemporalAdjusters.next(DayOfWeek.SUNDAY));
        return sunday.atTime(hour, minute).atZone(LAGOS).toInstant();
    }

    private RoutePost.RoutePostBuilder<?, ?> route(double[] from, double[] to) {
        return RoutePost.builder()
                .driverId(DRIVER)
                .vehicleId("vehicle-1")
                .originLabel("Church X")
                .originLat(from[0]).originLng(from[1])
                .destinationLabel(to == AJAH ? "Ajah" : to == LEKKI ? "Lekki" : to == BADAGRY ? "Badagry" : "Ajah")
                .destinationLat(to[0]).destinationLng(to[1])
                .departureTime(Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS))
                .seatCapacity(4)
                .seatsAvailable(4)
                .farePerSeat(FARE)
                .status(RouteStatus.PUBLISHED);
    }

    private Booking confirmed(RoutePost route, String riderId) {
        Booking booking = bookingService.reserveSeats(route.getId(), riderId,
                new BookSeatsRequest(null, 1, "Pickup point", route.getOriginLat(), route.getOriginLng()));
        bookingService.confirm(booking.getId());
        return bookingRepository.findById(booking.getId()).orElseThrow();
    }

    /** Relative to the row's own timestamp, so the database's and the JVM's time zones never disagree. */
    private void createdMinutesAgo(String table, String id, int minutes) {
        jdbcTemplate.update("update " + table + " set created_at = created_at - make_interval(mins => ?) where id = ?", minutes, id);
    }
}
