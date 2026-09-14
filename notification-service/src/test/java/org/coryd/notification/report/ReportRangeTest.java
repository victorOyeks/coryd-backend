package org.coryd.notification.report;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ReportRangeTest {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

    @Test
    void containingCoversThePeriodTheAnchorFallsIn() {
        LocalDate wednesday = LocalDate.of(2026, 9, 16);

        ReportRange day = ReportRange.containing(ReportPeriod.DAILY, wednesday, LAGOS);
        assertThat(day.startDate()).isEqualTo(wednesday);
        assertThat(day.endDate()).isEqualTo(wednesday);

        ReportRange week = ReportRange.containing(ReportPeriod.WEEKLY, wednesday, LAGOS);
        assertThat(week.startDate()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(week.endDate()).isEqualTo(LocalDate.of(2026, 9, 20));

        ReportRange month = ReportRange.containing(ReportPeriod.MONTHLY, wednesday, LAGOS);
        assertThat(month.startDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(month.endDate()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void completedBeforeSkipsBackToTheLastFinishedPeriod() {
        LocalDate monday = LocalDate.of(2026, 9, 14);

        assertThat(ReportRange.completedBefore(ReportPeriod.DAILY, monday, LAGOS).startDate())
                .isEqualTo(LocalDate.of(2026, 9, 13));

        ReportRange week = ReportRange.completedBefore(ReportPeriod.WEEKLY, monday, LAGOS);
        assertThat(week.startDate()).isEqualTo(LocalDate.of(2026, 9, 7));
        assertThat(week.endDate()).isEqualTo(LocalDate.of(2026, 9, 13));

        ReportRange month = ReportRange.completedBefore(ReportPeriod.MONTHLY, monday, LAGOS);
        assertThat(month.startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(month.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
    }

    /** The window must be Lagos midnight to Lagos midnight, not UTC. */
    @Test
    void boundsAreLocalMidnight() {
        ReportRange day = ReportRange.containing(ReportPeriod.DAILY, LocalDate.of(2026, 9, 13), LAGOS);

        assertThat(day.from().toString()).isEqualTo("2026-09-12T23:00:00Z");
        assertThat(day.to().toString()).isEqualTo("2026-09-13T23:00:00Z");
    }

    @Test
    void precedingIsTheSameShapeOnePeriodBack() {
        ReportRange september = ReportRange.containing(ReportPeriod.MONTHLY, LocalDate.of(2026, 9, 16), LAGOS);
        ReportRange august = september.preceding(ReportPeriod.MONTHLY, LAGOS);

        assertThat(august.startDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(august.endDate()).isEqualTo(LocalDate.of(2026, 8, 31));
    }
}
