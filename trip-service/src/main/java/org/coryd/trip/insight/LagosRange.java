package org.coryd.trip.insight;

import org.coryd.common.exception.BadRequestException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** [from, to] in Lagos days, inclusive - the same boundaries as the emailed reports. Defaults: the last 7 days to today. */
public record LagosRange(LocalDate from, LocalDate to) {

    public static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    public static final int MAX_DAYS = 366;

    public static LagosRange of(LocalDate from, LocalDate to, int defaultDays) {
        LocalDate end = to == null ? LocalDate.now(LAGOS) : to;
        LocalDate start = from == null ? end.minusDays(defaultDays - 1L) : from;
        if (start.isAfter(end)) {
            throw new BadRequestException("from must be on or before to");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_DAYS) {
            throw new BadRequestException("The range can be at most " + MAX_DAYS + " days");
        }
        return new LagosRange(start, end);
    }

    public Instant since() {
        return from.atStartOfDay(LAGOS).toInstant();
    }

    /** Exclusive. */
    public Instant until() {
        return to.plusDays(1).atStartOfDay(LAGOS).toInstant();
    }

    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }
}
