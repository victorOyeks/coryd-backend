package org.coryd.trip.insight;

import org.coryd.common.enums.BookingStatus;
import org.coryd.common.enums.RouteStatus;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.trip.config.StuckTripProperties;
import org.coryd.trip.entity.Booking;
import org.coryd.trip.entity.OpsAuditLog;
import org.coryd.trip.entity.RoutePost;
import org.coryd.trip.repository.BookingRepository;
import org.coryd.trip.repository.OpsAuditLogRepository;
import org.coryd.trip.repository.RoutePostRepository;
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

import java.io.StringWriter;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The dashboard's trip numbers and the trip reports against seeded data - every figure checked by hand. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
@Import({OpsDashboardService.class, OpsReportService.class, StuckTripProperties.class})
class OpsDashboardAndReportsIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            org.testcontainers.utility.DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"));

    @Autowired OpsDashboardService dashboardService;
    @Autowired OpsReportService reportService;
    @Autowired RoutePostRepository routes;
    @Autowired BookingRepository bookings;
    @Autowired OpsAuditLogRepository audit;
    @Autowired JdbcTemplate jdbc;

    LocalDate today = LocalDate.now(LagosRange.LAGOS);

    @BeforeEach
    void seed() {
        jdbc.update("delete from trip.cancellation_log");
        audit.deleteAll();
        bookings.deleteAll();
        routes.deleteAll();
        reportService.maxCsvRows(OpsReportService.MAX_CSV_ROWS);

        RoutePost done1 = route("driver-a", RouteStatus.COMPLETED, 4, Instant.now().minus(3, ChronoUnit.HOURS));
        RoutePost done2 = route("driver-b", RouteStatus.COMPLETED, 3, Instant.now().minus(2, ChronoUnit.HOURS));
        RoutePost cancelled = route("driver-a", RouteStatus.CANCELLED, 4, Instant.now().plus(5, ChronoUnit.HOURS));
        RoutePost soon = route("driver-c", RouteStatus.PUBLISHED, 4, Instant.now().plus(1, ChronoUnit.HOURS));
        RoutePost live = route("driver-d", RouteStatus.IN_PROGRESS, 2, Instant.now().minus(30, ChronoUnit.MINUTES));
        seat(done1, "rider-1", 2, "2400", BookingStatus.COMPLETED);
        seat(done1, "rider-2", 1, "1200", BookingStatus.COMPLETED);
        seat(done2, "rider-1", 1, "1200", BookingStatus.COMPLETED);
        seat(soon, "rider-3", 1, "1200", BookingStatus.CANCELLED_RIDER);
        seat(soon, "rider-4", 1, "1200", BookingStatus.HELD);
        seat(live, "rider-2", 1, "1200", BookingStatus.IN_PROGRESS);
        cancellation("ROUTE", cancelled.getId(), null, "DRIVER");
        cancellation("BOOKING", soon.getId(), UUID.randomUUID().toString(), "RIDER");
        audit.save(OpsAuditLog.builder().actorId("admin-1").actorName("Ada Ops").action(OpsAuditLog.Action.SETTING_CHANGED)
                .targetType(OpsAuditLog.TargetType.SETTING).targetId("booking.grace-minutes").reason("Riders kept missing it").build());
    }

    @Test
    void dashboardNumbersMatchTheSeededTrips() {
        TripDashboard d = dashboardService.dashboard(today.minusDays(1), today);
        assertThat(d.trips().posted()).isEqualTo(5);
        assertThat(d.trips().completed()).isEqualTo(2);
        assertThat(d.trips().cancelled()).isEqualTo(1);
        assertThat(d.trips().inProgressNow()).isEqualTo(1);
        assertThat(d.trips().completionRate()).isEqualTo(66.7);
        assertThat(d.trips().cancellationRate()).isEqualTo(20.0);
        assertThat(d.seats().offered()).isEqualTo(17);
        assertThat(d.seats().booked()).as("held seats aren't bookings").isEqualTo(6);
        assertThat(d.seats().avgSeatsFilledPerCompletedTrip()).isEqualTo(2.0);
        assertThat(d.drivers().activeInRange()).isEqualTo(4);
        assertThat(d.drivers().onTripNow()).isEqualTo(1);
        assertThat(d.drivers().onlineNow()).as("on a trip, or leaving within 2 hours").isEqualTo(2);
        assertThat(d.riders().activeInRange()).isEqualTo(3);
        assertThat(d.riders().repeatInRange()).isEqualTo(2);
        assertThat(d.bookings().made()).isEqualTo(5);
        assertThat(d.bookings().completed()).isEqualTo(3);
        assertThat(d.bookings().cancelledByRider()).isEqualTo(1);
        assertThat(d.bookings().confirmedOrUnderway()).isEqualTo(1);
        assertThat(d.grossFaresCompleted()).isEqualByComparingTo("4800");
        assertThat(d.cancellationsBySource()).containsEntry("DRIVER", 1L).containsEntry("RIDER", 1L);
        assertThat(d.cancellationsByKind()).containsEntry("ROUTE", 1L).containsEntry("BOOKING", 1L);
        assertThat(d.daily()).hasSize(2);
        TripDashboard.Day todayRow = d.daily().get(1);
        assertThat(todayRow.date()).isEqualTo(today);
        assertThat(todayRow.tripsPosted()).isEqualTo(5);
        assertThat(todayRow.bookings()).isEqualTo(5);
        assertThat(todayRow.cancellations()).isEqualTo(2);
        assertThat(todayRow.grossFaresCompleted()).isEqualByComparingTo("4800");
        assertThat(d.recentActivity()).isNotEmpty();
        assertThat(d.recentActivity()).anyMatch(a -> a.kind().equals("OPS_SETTING_CHANGED") && "Ada Ops".equals(a.actorName()));
        assertThat(d.recentActivity()).anyMatch(a -> a.kind().equals("TRIP_POSTED"));
        assertThat(d.recentActivity()).isSortedAccordingTo((a, b) -> b.at().compareTo(a.at()));
    }

    @Test
    void anOutOfRangeWindowIsRefused() {
        assertThatThrownBy(() -> dashboardService.dashboard(today, today.minusDays(1))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> dashboardService.dashboard(today.minusDays(400), today)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> TripReports.bySlug("payroll")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reportsPreviewAndExportTheSameRows() {
        OpsReportService.Preview trips = reportService.preview(TripReports.TRIPS, today.minusDays(1), today, 100);
        assertThat(trips.totalRows()).isEqualTo(5);
        assertThat(trips.rows()).hasSize(5);
        assertThat(trips.columns()).first().isEqualTo("trip_id");

        OpsReportService.Preview drivers = reportService.preview(TripReports.DRIVER_PERFORMANCE, today.minusDays(1), today, 100);
        assertThat(drivers.rows()).anySatisfy(r -> {
            assertThat(r.get(0)).isEqualTo("driver-a");
            assertThat(((Number) r.get(1)).longValue()).isEqualTo(2);
            assertThat(((Number) r.get(2)).longValue()).isEqualTo(1);
            assertThat(r.get(8)).as("money: a number, 2 dp").isEqualTo(new java.math.BigDecimal("3600.00"));
        });
        OpsReportService.Preview riders = reportService.preview(TripReports.RIDERS, today.minusDays(1), today, 100);
        assertThat(riders.totalRows()).as("the unpaid hold is no booking").isEqualTo(3);
        OpsReportService.Preview corridors = reportService.preview(TripReports.CORRIDORS, today.minusDays(1), today, 100);
        assertThat(corridors.rows()).singleElement().satisfies(r -> {
            assertThat(r.get(0)).isEqualTo("ikorodu->victoria island");
            assertThat(((Number) r.get(3)).longValue()).isEqualTo(5);
        });
        assertThat(reportService.preview(TripReports.CANCELLATIONS, today.minusDays(1), today, 100).totalRows()).isEqualTo(2);
        OpsReportService.Preview limited = reportService.preview(TripReports.BOOKINGS, today.minusDays(1), today, 2);
        assertThat(limited.rows()).as("limit").hasSize(2);
        assertThat(limited.limited()).as("the preview limit applied").isTrue();
        assertThat(limited.truncated()).as("truncated only when the CSV cap is hit").isFalse();
        assertThat(trips.limited()).isFalse();

        StringWriter csv = new StringWriter();
        int rows = reportService.csv(TripReports.BOOKINGS, LagosRange.of(today.minusDays(1), today, 30), csv);
        assertThat(rows).isEqualTo(5);
        String[] lines = csv.toString().split("\r\n");
        assertThat(lines[0]).isEqualTo(String.join(",", TripReports.BOOKINGS.columns()));
        assertThat(lines).hasSize(6);
    }

    @Test
    void everyReportColumnHasOneDeclaredTypeAndEveryValueMatchesIt() {
        for (TripReports report : TripReports.values()) {
            for (String column : report.columns()) {
                assertThat(ReportColumnType.declared(column)).as(report.slug() + "." + column + " has a declared type").isTrue();
            }
            OpsReportService.Preview preview = reportService.preview(report, today.minusDays(1), today, 500);
            assertThat(preview.columnTypes()).hasSameSizeAs(preview.columns());
            for (List<Object> row : preview.rows()) {
                for (int c = 0; c < row.size(); c++) {
                    Object v = row.get(c);
                    if (v == null) {
                        continue;
                    }
                    String what = report.slug() + "." + preview.columns().get(c) + " = " + v;
                    switch (preview.columnTypes().get(c)) {
                        case "integer" -> assertThat(v).as(what).isInstanceOf(Long.class);
                        case "decimal" -> assertThat(v).as(what).isInstanceOf(java.math.BigDecimal.class);
                        case "money" -> assertThat(((java.math.BigDecimal) v).scale()).as(what).isEqualTo(2);
                        case "date" -> assertThat((String) v).as(what).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
                        case "boolean" -> assertThat(v).as(what).isInstanceOf(Boolean.class);
                        default -> assertThat(v).as(what).isInstanceOf(String.class);
                    }
                }
            }
        }
        reportService.maxCsvRows(3);
        assertThat(reportService.preview(TripReports.TRIPS, today.minusDays(1), today, 100).truncated())
                .as("more rows than the CSV holds").isTrue();
    }

    @Test
    void theCsvExportIsBounded() {
        reportService.maxCsvRows(3);
        StringWriter csv = new StringWriter();
        LagosRange range = LagosRange.of(today.minusDays(1), today, 30);
        assertThat(reportService.csv(TripReports.TRIPS, range, csv)).isEqualTo(3);
        assertThat(csv.toString().split("\r\n")).hasSize(4);
        assertThat(reportService.count(TripReports.TRIPS, range)).as("the header says how many there were").isEqualTo(5);
    }

    @Test
    void csvCellsAreQuotedAndFormulasNeutralised() {
        assertThat(Csv.line(java.util.List.of("a,b", "say \"hi\"", "=cmd()", -5, "-12.5"))).isEqualTo("\"a,b\",\"say \"\"hi\"\"\",'=cmd(),-5,-12.5\r\n");
    }

    private RoutePost route(String driverId, RouteStatus status, int seats, Instant departure) {
        return routes.save(RoutePost.builder().driverId(driverId).vehicleId("vehicle-1")
                .originLabel("Ikorodu").originLat(6.62).originLng(3.51)
                .destinationLabel("Victoria  Island").destinationLat(6.43).destinationLng(3.42)
                .departureTime(departure).seatCapacity(seats).seatsAvailable(seats).farePerSeat(new BigDecimal("1200.00"))
                .status(status).startedAt(status == RouteStatus.IN_PROGRESS ? departure : null).build());
    }

    private void seat(RoutePost route, String riderId, int seats, String fare, BookingStatus status) {
        bookings.save(Booking.builder().routePostId(route.getId()).riderId(riderId).seatCount(seats).fareTotal(new BigDecimal(fare))
                .status(status).holdExpiresAt(Instant.now()).confirmedAt(Instant.now()).build());
    }

    private void cancellation(String kind, String routeId, String bookingId, String source) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                insert into trip.cancellation_log (id, kind, route_post_id, booking_id, source, cancelled_at, departure_time,
                    minutes_before_departure, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 60, ?, ?)
                """, UUID.randomUUID().toString(), kind, routeId, bookingId, source, now, now, now, now);
    }
}
