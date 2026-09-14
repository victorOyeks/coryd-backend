package org.coryd.notification.service;

import org.coryd.notification.client.PlatformStatsClient.IdentityStats;
import org.coryd.notification.client.PlatformStatsClient.PaymentStats;
import org.coryd.notification.client.PlatformStatsClient.ProfileStats;
import org.coryd.notification.client.PlatformStatsClient.TopRoute;
import org.coryd.notification.client.PlatformStatsClient.TripStats;
import org.coryd.notification.report.ReportData;
import org.coryd.notification.report.ReportPeriod;
import org.coryd.notification.report.ReportRange;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportEmailBuilderTest {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    private final ReportEmailBuilder builder = new ReportEmailBuilder();

    @Test
    void rendersAllThreePeriods() throws IOException {
        Path out = Path.of("target", "report-previews");
        Files.createDirectories(out);

        for (ReportPeriod period : ReportPeriod.values()) {
            ReportData data = sample(period);
            String html = builder.build(data);

            assertThat(html).startsWith("<body").endsWith("</body>");
            assertThat(html).contains("CoRyd").contains("&#8358;");
            assertThat(html).doesNotContain("null");
            assertThat(builder.subject(data)).contains("NGN").contains("seats");

            Files.writeString(out.resolve(period.name().toLowerCase() + ".html"), html);
        }
    }

    @Test
    void escapesRouteLabels() {
        ReportData data = sample(ReportPeriod.WEEKLY);
        assertThat(builder.build(data)).doesNotContain("<script>");
    }

    @Test
    void handlesAnEmptyPeriodWithoutBreaking() {
        ReportRange range = ReportRange.previousDay(LocalDate.of(2026, 9, 14), LAGOS);
        ReportData data = new ReportData(ReportPeriod.DAILY, range,
                TripStats.EMPTY, PaymentStats.EMPTY, IdentityStats.EMPTY, ProfileStats.EMPTY,
                TripStats.EMPTY, PaymentStats.EMPTY, List.of(), List.of());

        assertThat(data.seatFillRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(data.grossChangePercent()).isNull();
        assertThat(builder.build(data)).contains("&#8358;0");
    }

    private ReportData sample(ReportPeriod period) {
        LocalDate today = LocalDate.of(2026, 9, 14);
        ReportRange range = switch (period) {
            case DAILY -> ReportRange.previousDay(today, LAGOS);
            case WEEKLY -> ReportRange.previousWeek(today, LAGOS);
            case MONTHLY -> ReportRange.previousMonth(today, LAGOS);
        };

        TripStats trip = new TripStats(34, 29, 3, 136, 41, 97, 88, 6, 2, 1, 97, 62, 24,
                List.of(new TopRoute("Ikorodu", "Victoria Island", 148, new BigDecimal("1482000")),
                        new TopRoute("Ikeja", "<script>Lekki</script>", 112, new BigDecimal("1116000"))));

        PaymentStats payment = new PaymentStats(
                new BigDecimal("248500"), new BigDecimal("201000"), new BigDecimal("47500"),
                new BigDecimal("12425"), new BigDecimal("236075"),
                8, new BigDecimal("14000"), 12, new BigDecimal("180200"), new BigDecimal("167620"));

        PaymentStats previous = new PaymentStats(
                new BigDecimal("221000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO);

        List<ReportData.Bucket> breakdown = period == ReportPeriod.DAILY ? List.of() : List.of(
                new ReportData.Bucket("Mon", new BigDecimal("241000")),
                new ReportData.Bucket("Fri", new BigDecimal("325100")),
                new ReportData.Bucket("Sun", new BigDecimal("25600")));

        List<ReportData.Bucket> trend = period == ReportPeriod.MONTHLY ? List.of(
                new ReportData.Bucket("Jul", new BigDecimal("5144000")),
                new ReportData.Bucket("Aug", new BigDecimal("6410800"))) : List.of();

        return new ReportData(period, range, trip, payment,
                new IdentityStats(18, 4, 1842, 68),
                new ProfileStats(68, 7, Instant.now().minusSeconds(4 * 86400)),
                new TripStats(37, 30, 2, 140, 39, 89, 80, 5, 2, 1, 89, 58, 20, List.of()),
                previous, breakdown, trend);
    }
}
