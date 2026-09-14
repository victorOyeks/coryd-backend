package org.coryd.notification.report;

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

    public static ReportRange previousDay(LocalDate today, ZoneId zone) {
        LocalDate day = today.minusDays(1);
        return of(day, day, zone);
    }

    public static ReportRange previousWeek(LocalDate today, ZoneId zone) {
        LocalDate lastMonday = today.with(TemporalAdjusters.previous(java.time.DayOfWeek.MONDAY));
        return of(lastMonday, lastMonday.plusDays(6), zone);
    }

    public static ReportRange previousMonth(LocalDate today, ZoneId zone) {
        LocalDate firstOfLastMonth = today.withDayOfMonth(1).minusMonths(1);
        return of(firstOfLastMonth, firstOfLastMonth.with(TemporalAdjusters.lastDayOfMonth()), zone);
    }

    /** The equivalent window immediately before this one, for the period-on-period deltas. */
    public ReportRange preceding(ReportPeriod period, ZoneId zone) {
        return switch (period) {
            case DAILY -> of(startDate.minusDays(1), endDate.minusDays(1), zone);
            case WEEKLY -> of(startDate.minusWeeks(1), endDate.minusWeeks(1), zone);
            case MONTHLY -> {
                LocalDate start = startDate.minusMonths(1);
                yield of(start, start.with(TemporalAdjusters.lastDayOfMonth()), zone);
            }
        };
    }
}
