package org.coryd.trip.service;

import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.trip.dto.OpsPricingResponse;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.entity.SuggestedFareRange;
import org.coryd.trip.repository.RoutePostRepository;
import org.coryd.trip.repository.SuggestedFareRangeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Ops Console pricing monitor: fares per corridor against the suggested range, outliers, riders' fare reports. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import(OpsPricingService.class)
class OpsPricingServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @Autowired OpsPricingService pricingService;
    @Autowired RoutePostRepository routePostRepository;
    @Autowired SuggestedFareRangeRepository suggestedFareRangeRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanSlate() {
        jdbcTemplate.update("delete from trip.issue_reports");
        jdbcTemplate.update("delete from trip.bookings");
        routePostRepository.deleteAll();
        suggestedFareRangeRepository.deleteAll();
    }

    @Test
    void faresAreComparedWithTheCorridorsSuggestedRange() {
        suggestedFareRangeRepository.save(SuggestedFareRange.builder().corridorKey("yaba->lekki phase 1")
                .minFare(new BigDecimal("1500")).maxFare(new BigDecimal("2500")).sampleSize(12).build());
        route("Yaba", "Lekki  Phase 1", "2000");
        RoutePost high = route(" yaba", "Lekki Phase 1", "5000");
        route("Yaba", "Lekki Phase 1", "1000");
        route("Ikeja", "Victoria Island", "3000");
        RoutePost draft = route("Yaba", "Lekki Phase 1", "9000");
        draft.setStatus(RouteStatus.DRAFT);
        routePostRepository.save(draft);
        String booking = booking(high);
        jdbcTemplate.update("insert into trip.issue_reports (id, booking_id, reported_by, category, description, reference_number, status,"
                + " created_at, updated_at) values (?, ?, 'rider-1', 'PAYMENT', 'Charged far too much', 'ISS-00000001', 'OPEN', now(), now())",
                UUID.randomUUID().toString(), booking);
        jdbcTemplate.update("insert into trip.issue_reports (id, booking_id, reported_by, category, description, reference_number, status,"
                + " created_at, updated_at) values (?, ?, 'rider-1', 'SAFETY', 'Unrelated', 'ISS-00000002', 'OPEN', now(), now())",
                UUID.randomUUID().toString(), booking);

        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
        OpsPricingResponse result = pricingService.pricing(today.minusDays(1), today.plusDays(3), null, false);

        assertThat(result.totals().routes()).isEqualTo(4);
        assertThat(result.totals().corridors()).isEqualTo(2);
        assertThat(result.totals().outliers()).isEqualTo(2);
        assertThat(result.totals().riderFareReports()).isEqualTo(1);
        OpsPricingResponse.Corridor yaba = result.corridors().get(0);
        assertThat(yaba.corridorKey()).isEqualTo("yaba->lekki phase 1");
        assertThat(yaba.routes()).isEqualTo(3);
        assertThat(yaba.fareMin()).isEqualByComparingTo("1000");
        assertThat(yaba.fareMedian()).isEqualByComparingTo("2000");
        assertThat(yaba.fareMax()).isEqualByComparingTo("5000");
        assertThat(yaba.suggestedMin()).isEqualByComparingTo("1500");
        assertThat(yaba.outliers()).isEqualTo(2);
        assertThat(yaba.riderFareReports()).isEqualTo(1);
        assertThat(result.corridors().get(1).suggestedMin()).isNull();

        assertThat(result.outliers()).hasSize(2);
        OpsPricingResponse.Outlier worst = result.outliers().get(0);
        assertThat(worst.routePostId()).isEqualTo(high.getId());
        assertThat(worst.direction()).isEqualTo("ABOVE");
        assertThat(worst.deviationPercent()).isEqualByComparingTo("100.0");
        assertThat(result.outliers().get(1).direction()).isEqualTo("BELOW");
        assertThat(result.riderFareReports()).singleElement().satisfies(r -> {
            assertThat(r.referenceNumber()).isEqualTo("ISS-00000001");
            assertThat(r.routePostId()).isEqualTo(high.getId());
        });

        assertThat(pricingService.pricing(today.minusDays(1), today.plusDays(3), "ikeja", false).corridors()).hasSize(1);
        assertThat(pricingService.pricing(today.minusDays(1), today.plusDays(3), null, true).corridors())
                .extracting(OpsPricingResponse.Corridor::corridorKey).containsExactly("yaba->lekki phase 1");
        assertThatThrownBy(() -> pricingService.pricing(today, today.minusDays(1), null, false)).isInstanceOf(BadRequestException.class);
    }

    private RoutePost route(String origin, String destination, String fare) {
        return routePostRepository.save(RoutePost.builder()
                .driverId("driver-1").vehicleId("vehicle-1")
                .originLabel(origin).originLat(6.45).originLng(3.39)
                .destinationLabel(destination).destinationLat(6.44).destinationLng(3.40)
                .departureTime(Instant.now().plus(1, ChronoUnit.DAYS))
                .seatCapacity(4).seatsAvailable(4)
                .farePerSeat(new BigDecimal(fare))
                .status(RouteStatus.PUBLISHED)
                .build());
    }

    private String booking(RoutePost route) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("insert into trip.bookings (id, route_post_id, rider_id, seat_count, fare_total, status, hold_expires_at,"
                + " created_at, updated_at) values (?, ?, 'rider-1', 1, ?, 'CONFIRMED', now(), now(), now())",
                id, route.getId(), route.getFarePerSeat());
        return id;
    }
}
