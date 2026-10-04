package org.coryd.payment.util;

import org.coryd.payment.dto.EarningsPeriod;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;

/** Shared [since, until) computation for the earnings screen's period tabs - the summary card and the trip list both use this, so the numbers always agree. Exclusive upper bound works whether the period is the current one (bound sits in the future, harmless) or a past one picked via the month picker. */
public final class PeriodRange {

    private PeriodRange() {
    }

    public record Range(Instant since, Instant until) {
    }

    public static Range of(EarningsPeriod period, YearMonth month) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return switch (period) {
            case TODAY -> {
                Instant since = today.atStartOfDay(ZoneOffset.UTC).toInstant();
                yield new Range(since, since.plus(1, ChronoUnit.DAYS));
            }
            case WEEK -> {
                Instant since = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .atStartOfDay(ZoneOffset.UTC).toInstant();
                yield new Range(since, since.plus(7, ChronoUnit.DAYS));
            }
            case MONTH -> {
                YearMonth target = month != null ? month : YearMonth.now(ZoneOffset.UTC);
                Instant since = target.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                Instant until = target.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                yield new Range(since, until);
            }
        };
    }
}
