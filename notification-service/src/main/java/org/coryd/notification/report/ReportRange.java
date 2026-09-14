package org.coryd.notification.report;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * A closed-open instant window derived from local calendar dates - every report covers whole
 * days in the operating timezone, never UTC days, so a 6am Lagos report reflects a Lagos day.
 */
public record ReportRange(LocalDate startDate, LocalDate endDate, Instant from, Instant to) {

    public static ReportRange of(LocalDate startDate, LocalDate endDate, ZoneId zone) {
        return new ReportRange(
                startDate,
                endDate,
                startDate.atStartOfDay(zone).toInstant(),
                endDate.plusDays(1).atStartOfDay(zone).toInstant());
    }

    /** The period containing the anchor date - what a manual trigger reports on. */
    public static ReportRange containing(ReportPeriod period, LocalDate anchor, ZoneId zone) {
        return switch (period) {
            case DAILY -> of(anchor, anchor, zone);
            case WEEKLY -> {
                LocalDate monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                yield of(monday, monday.plusDays(6), zone);
            }
            case MONTHLY -> of(anchor.withDayOfMonth(1), anchor.with(TemporalAdjusters.lastDayOfMonth()), zone);
        };
    }

    /** The most recently completed period - what the scheduled jobs report on. */
    public static ReportRange completedBefore(ReportPeriod period, LocalDate today, ZoneId zone) {
        LocalDate anchor = switch (period) {
            case DAILY -> today.minusDays(1);
            case WEEKLY -> today.minusWeeks(1);
            case MONTHLY -> today.withDayOfMonth(1).minusMonths(1);
        };
        return containing(period, anchor, zone);
    }

    /** The equivalent window immediately before this one, for the period-on-period deltas. */
    public ReportRange preceding(ReportPeriod period, ZoneId zone) {
        LocalDate anchor = switch (period) {
            case DAILY -> startDate.minusDays(1);
            case WEEKLY -> startDate.minusWeeks(1);
            case MONTHLY -> startDate.minusMonths(1);
        };
        return containing(period, anchor, zone);
    }
}
