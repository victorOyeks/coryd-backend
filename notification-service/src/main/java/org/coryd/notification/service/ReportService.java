package org.coryd.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.UserRole;
import org.coryd.common.exception.UnauthorizedException;
import org.coryd.notification.client.PlatformStatsClient;
import org.coryd.notification.gateway.EmailService;
import org.coryd.notification.report.ReportData;
import org.coryd.notification.report.ReportEmailBuilder;
import org.coryd.notification.report.ReportPeriod;
import org.coryd.notification.report.ReportRange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("EEE");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM");
    private static final DateTimeFormatter WEEK_LABEL = DateTimeFormatter.ofPattern("d MMM");
    private static final int TREND_MONTHS = 6;

    private final PlatformStatsClient statsClient;
    private final ReportEmailBuilder emailBuilder;
    private final EmailService emailService;

    @Value("${report.timezone:Africa/Lagos}")
    private String timezone;

    /** Comma-separated in REPORT_RECIPIENTS; add as many as you like. */
    @Value("${report.recipients:}")
    private List<String> recipients;

    /** Scheduled path - the most recently completed period. */
    public void sendReport(ReportPeriod period) {
        ZoneId zone = ZoneId.of(timezone);
        send(period, ReportRange.completedBefore(period, LocalDate.now(zone), zone), zone, cleanRecipients());
    }

    /**
     * Manual trigger. anchor is any date inside the period to report on, defaulting to today,
     * so a report can be fired for the current day, week or month before it has finished.
     */
    public void sendReportFor(ReportPeriod period, LocalDate anchor, UserRole callerRole, List<String> overrideTo) {
        requireOpsAdmin(callerRole);
        ZoneId zone = ZoneId.of(timezone);
        List<String> to = overrideTo == null || overrideTo.isEmpty() ? cleanRecipients() : clean(overrideTo);
        send(period, ReportRange.containing(period, anchorOrToday(anchor, zone), zone), zone, to);
    }

    /** Renders the report without sending it, so a trigger can be eyeballed without spending send quota. */
    public String renderReportFor(ReportPeriod period, LocalDate anchor, UserRole callerRole) {
        requireOpsAdmin(callerRole);
        ZoneId zone = ZoneId.of(timezone);
        ReportRange range = ReportRange.containing(period, anchorOrToday(anchor, zone), zone);
        return emailBuilder.build(collect(period, range, zone));
    }

    private void send(ReportPeriod period, ReportRange range, ZoneId zone, List<String> to) {
        ReportData data = collect(period, range, zone);
        boolean sent = emailService.sendHtml(to, emailBuilder.subject(data), emailBuilder.build(data));
        log.info("{} report for {}..{} {} to {} recipient(s)",
                period, range.startDate(), range.endDate(), sent ? "sent" : "NOT sent", to.size());
    }

    private LocalDate anchorOrToday(LocalDate anchor, ZoneId zone) {
        return anchor == null ? LocalDate.now(zone) : anchor;
    }

    private static void requireOpsAdmin(UserRole callerRole) {
        if (callerRole != UserRole.OPS_ADMIN) {
            throw new UnauthorizedException("Only an ops admin can trigger reports");
        }
    }

    /** Tolerates spaces and a trailing comma - one blank entry would otherwise fail the whole send. */
    private List<String> cleanRecipients() {
        return clean(recipients);
    }

    private static List<String> clean(List<String> addresses) {
        if (addresses == null) {
            return List.of();
        }
        return addresses.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(address -> !address.isEmpty())
                .distinct()
                .toList();
    }

    private ReportData collect(ReportPeriod period, ReportRange range, ZoneId zone) {
        ReportRange previous = range.preceding(period, zone);
        return new ReportData(
                period,
                range,
                statsClient.tripStats(range.from(), range.to()),
                statsClient.paymentStats(range.from(), range.to()),
                statsClient.identityStats(range.from(), range.to()),
                statsClient.profileStats(),
                statsClient.tripStats(previous.from(), previous.to()),
                statsClient.paymentStats(previous.from(), previous.to()),
                breakdown(period, range, zone),
                trend(period, range, zone));
    }

    /** Day-by-day bars for the weekly report, week-by-week for the monthly. Daily has none. */
    private List<ReportData.Bucket> breakdown(ReportPeriod period, ReportRange range, ZoneId zone) {
        List<ReportData.Bucket> buckets = new ArrayList<>();
        switch (period) {
            case DAILY -> {
            }
            case WEEKLY -> {
                for (LocalDate day = range.startDate(); !day.isAfter(range.endDate()); day = day.plusDays(1)) {
                    buckets.add(bucket(DAY_LABEL.format(day), ReportRange.of(day, day, zone)));
                }
            }
            case MONTHLY -> {
                LocalDate cursor = range.startDate();
                while (!cursor.isAfter(range.endDate())) {
                    LocalDate weekEnd = cursor.with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY));
                    if (weekEnd.isAfter(range.endDate())) {
                        weekEnd = range.endDate();
                    }
                    String label = WEEK_LABEL.format(cursor) + " – " + WEEK_LABEL.format(weekEnd);
                    buckets.add(bucket(label, ReportRange.of(cursor, weekEnd, zone)));
                    cursor = weekEnd.plusDays(1);
                }
            }
        }
        return buckets;
    }

    /** Six-month gross-fare trend, monthly report only. */
    private List<ReportData.Bucket> trend(ReportPeriod period, ReportRange range, ZoneId zone) {
        if (period != ReportPeriod.MONTHLY) {
            return List.of();
        }
        List<ReportData.Bucket> buckets = new ArrayList<>();
        for (int back = TREND_MONTHS - 1; back >= 0; back--) {
            LocalDate monthStart = range.startDate().minusMonths(back);
            LocalDate monthEnd = monthStart.with(TemporalAdjusters.lastDayOfMonth());
            buckets.add(bucket(MONTH_LABEL.format(monthStart), ReportRange.of(monthStart, monthEnd, zone)));
        }
        return buckets;
    }

    private ReportData.Bucket bucket(String label, ReportRange range) {
        PlatformStatsClient.PaymentStats stats = statsClient.paymentStats(range.from(), range.to());
        return new ReportData.Bucket(label, stats.grossFares());
    }
}
