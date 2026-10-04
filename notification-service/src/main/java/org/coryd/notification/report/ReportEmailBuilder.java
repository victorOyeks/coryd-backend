package org.coryd.notification.report;

import org.coryd.notification.client.PlatformStatsClient.TopRoute;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Renders the ops report as email-client-safe HTML: table layout, inline styles, no SVG or
 * flexbox. Bars are nested tables with bgcolor, the only chart technique Gmail and Outlook
 * both honour.
 */
@Component
public class ReportEmailBuilder {

    private static final String NAIRA = "&#8358;";
    private static final String INK = "#121212";
    private static final String AMBER = "#F5B301";
    private static final String GREEN = "#1B8F3A";
    private static final String RED = "#D93025";
    private static final String MUTED = "#8A8A8A";
    private static final String HAIRLINE = "#F2F2F2";
    private static final String FONT = "-apple-system,'Segoe UI',Helvetica,Arial,sans-serif";

    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SUBJECT_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    @Value("${report.logo-url:}")
    private String logoUrl;

    public String subject(ReportData data) {
        String period = switch (data.period()) {
            case DAILY -> "Daily";
            case WEEKLY -> "Weekly";
            case MONTHLY -> "Monthly";
        };
        String when = data.period() == ReportPeriod.MONTHLY
                ? MONTH_YEAR.format(data.range().startDate())
                : data.period() == ReportPeriod.WEEKLY
                        ? SUBJECT_DATE.format(data.range().startDate()) + "–" + SUBJECT_DATE.format(data.range().endDate())
                        : SUBJECT_DATE.format(data.range().startDate());
        return "CoRyd %s · %s · %s · %d seats".formatted(
                period, when, plainNaira(data.payment().grossFares()), data.trip().seatsBooked());
    }

    public String build(ReportData data) {
        StringBuilder html = new StringBuilder(8192);
        html.append("<body style=\"margin:0;padding:24px 12px;background:#E9E9E9;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("style=\"max-width:600px;margin:0 auto;background:#FFFFFF;border-radius:14px;")
                .append("overflow:hidden;font-family:").append(FONT).append(";\">");

        header(html, data);
        hero(html, data);
        tiles(html, data);

        switch (data.period()) {
            case DAILY -> {
                routesSection(html, data);
                bookingsSection(html, data);
                moneySection(html, data);
                growthSection(html, data);
            }
            case WEEKLY -> {
                barSection(html, "Day by day", data.breakdown());
                topRoutesSection(html, data, false);
                healthSection(html, data);
                moneySection(html, data);
                growthSection(html, data);
            }
            case MONTHLY -> {
                barSection(html, "Week by week", data.breakdown());
                barSection(html, "Six-month trend", data.trend());
                moneySection(html, data);
                healthSection(html, data);
                topRoutesSection(html, data, true);
            }
        }

        attentionSection(html, data);
        footer(html, data);

        html.append("</table></body>");
        return html.toString();
    }

    // ── Header and hero ──────────────────────────────────────────────────────

    private void header(StringBuilder html, ReportData data) {
        html.append("<tr><td style=\"background:").append(INK).append(";padding:28px 32px 26px;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td valign=\"middle\">");

        if (logoUrl != null && !logoUrl.isBlank()) {
            html.append("<img src=\"").append(escape(logoUrl)).append("\" width=\"130\" alt=\"CoRyd\" ")
                    .append("style=\"display:block;border:0;\">");
        } else {
            html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
                    .append("<td valign=\"bottom\" style=\"padding-right:10px;padding-bottom:4px;\">")
                    .append("<div style=\"width:8px;height:8px;background:").append(AMBER)
                    .append(";border-radius:8px;font-size:0;line-height:0;\">&nbsp;</div></td>")
                    .append("<td valign=\"middle\" style=\"font-size:24px;font-weight:700;color:#FFFFFF;")
                    .append("letter-spacing:-0.8px;line-height:1;\">CoRyd</td></tr></table>");
        }

        html.append("</td><td align=\"right\" valign=\"middle\" style=\"font-size:11px;color:#7A7A7A;")
                .append("letter-spacing:.9px;text-transform:uppercase;\">")
                .append(data.period().label())
                .append("</td></tr></table></td></tr>");
    }

    private void hero(StringBuilder html, ReportData data) {
        html.append("<tr><td style=\"background:").append(INK).append(";padding:6px 32px 34px;\">")
                .append("<div style=\"font-size:11px;color:#7A7A7A;letter-spacing:1.2px;text-transform:uppercase;")
                .append("padding-bottom:10px;\">").append(periodLabel(data)).append("</div>");

        if (data.period() == ReportPeriod.MONTHLY) {
            html.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
                    .append("<td valign=\"bottom\"><div style=\"font-size:42px;font-weight:700;color:#FFFFFF;")
                    .append("letter-spacing:-1.6px;line-height:1.05;\">").append(naira(data.payment().grossFares()))
                    .append("</div><div style=\"font-size:12px;color:#9A9A9A;padding-top:7px;\">gross fares ")
                    .append(deltaChip(data)).append("</div></td>")
                    .append("<td align=\"right\" valign=\"bottom\"><div style=\"font-size:26px;font-weight:700;color:")
                    .append(AMBER).append(";letter-spacing:-0.8px;line-height:1.05;\">")
                    .append(naira(data.payment().commission()))
                    .append("</div><div style=\"font-size:12px;color:#9A9A9A;padding-top:7px;\">net to CoRyd</div>")
                    .append("</td></tr></table>");
        } else {
            html.append("<div style=\"font-size:46px;font-weight:700;color:#FFFFFF;letter-spacing:-1.8px;")
                    .append("line-height:1.05;\">").append(naira(data.payment().grossFares())).append("</div>")
                    .append("<div style=\"font-size:13px;color:#9A9A9A;padding-top:8px;\">gross fares ")
                    .append(deltaChip(data)).append("</div>");
        }
        html.append("</td></tr>");
    }

    private void tiles(StringBuilder html, ReportData data) {
        html.append("<tr><td style=\"padding:0 20px;background:#FFFFFF;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("style=\"margin-top:-18px;\"><tr>");

        if (data.period() == ReportPeriod.MONTHLY) {
            tile(html, format(data.trip().seatsBooked()), "Seats booked");
            tile(html, format(data.trip().routesPosted()), "Routes posted");
            tile(html, naira(data.averageFare()), "Avg. fare");
        } else {
            tile(html, naira(data.payment().commission()), "Commission");
            tile(html, format(data.trip().seatsBooked()), "Seats booked");
            tile(html, format(data.trip().routesPosted()), "Routes posted");
        }

        html.append("</tr></table></td></tr>");
    }

    private void tile(StringBuilder html, String value, String label) {
        html.append("<td width=\"33.33%\" style=\"padding:0 5px;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("style=\"background:#FAFAFA;border:1px solid #EFEFEF;border-radius:10px;\"><tr>")
                .append("<td align=\"center\" style=\"padding:16px 8px 15px;\">")
                .append("<div style=\"font-size:22px;font-weight:700;color:").append(INK)
                .append(";letter-spacing:-0.5px;\">").append(value).append("</div>")
                .append("<div style=\"font-size:10px;color:").append(MUTED)
                .append(";letter-spacing:.7px;text-transform:uppercase;padding-top:5px;\">").append(label)
                .append("</div></td></tr></table></td>");
    }

    // ── Sections ─────────────────────────────────────────────────────────────

    private void routesSection(StringBuilder html, ReportData data) {
        openSection(html, "Routes");
        row(html, "Posted", format(data.trip().routesPosted()), INK, false);
        row(html, "Completed", format(data.trip().routesCompleted()), INK, false);
        row(html, "Cancelled", format(data.trip().routesCancelled()), RED, false);
        row(html, "Active drivers", format(data.trip().activeDrivers()), INK, true);
        closeSection(html);
    }

    private void bookingsSection(StringBuilder html, ReportData data) {
        openSection(html, "Bookings");
        row(html, "Confirmed", format(data.trip().bookingsConfirmed()), INK, false);
        row(html, "Completed", format(data.trip().bookingsCompleted()), INK, false);
        row(html, "Cancelled by rider", format(data.trip().bookingsCancelledRider()), RED, false);
        row(html, "Cancelled by driver", format(data.trip().bookingsCancelledDriver()), RED, false);
        row(html, "No-shows", format(data.trip().noShows()), RED, true);
        closeSection(html);
    }

    private void moneySection(StringBuilder html, ReportData data) {
        openSection(html, data.period() == ReportPeriod.MONTHLY ? "Money &middot; for the books" : "Money");
        row(html, "Gross fares collected", naira(data.payment().grossFares()), INK, false);
        row(html, "Commission earned", naira(data.payment().commission()), GREEN, false);
        row(html, "Credited to drivers", naira(data.payment().creditedToDrivers()), INK, false);
        row(html, "Refunds issued <span style=\"color:#AAA;\">&middot; " + data.payment().refundCount() + "</span>",
                naira(data.payment().refundAmount()), RED, false);
        boolean monthly = data.period() == ReportPeriod.MONTHLY;
        row(html, "Paid to driver banks <span style=\"color:#AAA;\">&middot; " + data.payment().payoutCount() + "</span>",
                naira(data.payment().payoutAmount()), INK, !monthly);
        if (monthly) {
            row(html, "Owed back by drivers", naira(data.payment().driverDebt()), INK, true);
        }
        closeSection(html);
    }

    private void healthSection(StringBuilder html, ReportData data) {
        openSection(html, data.period() == ReportPeriod.MONTHLY ? "Marketplace" : "Marketplace health");
        row(html, "Seat fill rate", percent(data.seatFillRate()), INK, false);
        row(html, "Trip completion rate", percent(data.completionRate()), INK, false);
        row(html, "Repeat riders <span style=\"color:#AAA;\">&middot; booked 2+ times</span>",
                percent(data.repeatRiderRate()), INK, false);
        row(html, "Active drivers",
                format(data.trip().activeDrivers()) + " <span style=\"color:#AAA;font-weight:400;\">of "
                        + format(data.profile().approvedDrivers()) + "</span>", INK, !isMonthly(data));
        if (isMonthly(data)) {
            row(html, "Total riders", format(data.identity().totalRiders()), INK, true);
        }
        closeSection(html);
    }

    private void growthSection(StringBuilder html, ReportData data) {
        openSection(html, "Growth");
        row(html, "New riders", format(data.identity().newRiders()), INK, false);
        row(html, "New drivers", format(data.identity().newDrivers()), INK, false);
        row(html, "Awaiting vehicle approval", format(data.profile().awaitingApproval()), "#B57D0A", false);
        row(html, "Operating without approved documents",
                format(data.trip().driversOperatingUnverified()), "#B57D0A", true);
        closeSection(html);
    }

    private void topRoutesSection(StringBuilder html, ReportData data, boolean byRevenue) {
        List<TopRoute> routes = data.trip().topRoutes();
        if (routes.isEmpty()) {
            return;
        }
        openSection(html, "Top routes");
        for (int i = 0; i < routes.size(); i++) {
            TopRoute route = routes.get(i);
            String label = escape(route.origin()) + " &rarr; " + escape(route.destination());
            String value = byRevenue
                    ? naira(route.grossFare())
                    : format(route.seats()) + " <span style=\"color:#AAA;font-weight:400;\">seats</span>";
            row(html, label, value, INK, i == routes.size() - 1);
        }
        closeSection(html);
    }

    private void barSection(StringBuilder html, String title, List<ReportData.Bucket> buckets) {
        if (buckets.isEmpty()) {
            return;
        }
        BigDecimal peak = buckets.stream()
                .map(ReportData.Bucket::grossFares)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        openSection(html, title);
        for (ReportData.Bucket bucket : buckets) {
            int width = barWidth(bucket.grossFares(), peak);
            boolean isPeak = peak.signum() > 0 && bucket.grossFares().compareTo(peak) == 0;
            html.append("<tr><td width=\"88\" style=\"padding:5px 0;color:").append(MUTED).append(";font-size:13px;\">")
                    .append(escape(bucket.label())).append("</td>")
                    .append("<td style=\"padding:5px 0;\">")
                    .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                    .append("style=\"background:#F4F4F4;border-radius:3px;\"><tr>")
                    .append("<td width=\"").append(width).append("%\" bgcolor=\"").append(isPeak ? AMBER : INK)
                    .append("\" style=\"height:14px;border-radius:3px;font-size:0;line-height:0;\">&nbsp;</td>");
            if (width < 100) {
                html.append("<td>&nbsp;</td>");
            }
            html.append("</tr></table></td>")
                    .append("<td width=\"92\" align=\"right\" style=\"padding:5px 0;font-weight:600;color:").append(INK)
                    .append(";font-size:13px;\">").append(naira(bucket.grossFares())).append("</td></tr>");
        }
        closeSection(html);
    }

    private void attentionSection(StringBuilder html, ReportData data) {
        List<String> items = attentionItems(data);
        if (items.isEmpty()) {
            return;
        }
        html.append("<tr><td style=\"padding:30px 32px 0;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("style=\"background:#FFFBF2;border:1px solid #F5E4BE;border-radius:10px;\"><tr>")
                .append("<td style=\"padding:18px 20px;\">")
                .append("<div style=\"font-size:11px;font-weight:700;color:#8A6410;letter-spacing:1.2px;")
                .append("text-transform:uppercase;padding-bottom:10px;\">Needs attention</div>")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("style=\"font-size:13px;color:#4A4030;line-height:1.5;\">");
        for (String item : items) {
            html.append("<tr><td style=\"padding:3px 0;\">").append(item).append("</td></tr>");
        }
        html.append("</table></td></tr></table></td></tr>");
    }

    private List<String> attentionItems(ReportData data) {
        List<String> items = new java.util.ArrayList<>();

        long pending = data.profile().awaitingApproval();
        if (pending > 0) {
            String age = "";
            Instant oldest = data.profile().oldestPendingAt();
            if (oldest != null) {
                long days = Duration.between(oldest, Instant.now()).toDays();
                age = " &mdash; oldest " + days + (days == 1 ? " day" : " days");
            }
            items.add(pending + " driver" + (pending == 1 ? "" : "s") + " waiting on vehicle approval" + age);
        }

        if (data.trip().graceExpiringSoon() > 0) {
            items.add(data.trip().graceExpiringSoon() + " driver"
                    + (data.trip().graceExpiringSoon() == 1 ? "'s" : "s'")
                    + " document window closes within 7 days &mdash; they stop being able to post routes");
        }

        long idleDrivers = data.profile().approvedDrivers() - data.trip().activeDrivers();
        if (idleDrivers > 0 && data.period() != ReportPeriod.DAILY) {
            items.add(idleDrivers + " of " + data.profile().approvedDrivers() + " approved drivers posted no routes");
        }

        if (data.trip().bookingsCancelledDriver() > 0) {
            items.add(data.trip().bookingsCancelledDriver() + " booking"
                    + (data.trip().bookingsCancelledDriver() == 1 ? "" : "s")
                    + " cancelled by drivers &mdash; "
                    + naira(data.payment().refundAmount()) + " refunded in total");
        }

        return items;
    }

    private void footer(StringBuilder html, ReportData data) {
        html.append("<tr><td style=\"padding:32px;\">")
                .append("<div style=\"border-top:1px solid #F0F0F0;padding-top:18px;font-size:11px;color:#A8A8A8;")
                .append("line-height:1.7;\">Automated report &middot; ")
                .append(SHORT_DATE.format(data.range().startDate()))
                .append(data.range().startDate().equals(data.range().endDate())
                        ? "" : " &ndash; " + SHORT_DATE.format(data.range().endDate()))
                .append(" ").append(data.range().startDate().getYear())
                .append(", Africa/Lagos<br>Kinetum Global Solutions Ltd &middot; RC 9825799 &middot; Lagos, Nigeria")
                .append("</div></td></tr>");
    }

    // ── Building blocks ──────────────────────────────────────────────────────

    private void openSection(StringBuilder html, String title) {
        html.append("<tr><td style=\"padding:30px 32px 0;\">")
                .append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\"><tr>")
                .append("<td valign=\"middle\" style=\"padding-right:8px;\">")
                .append("<div style=\"width:5px;height:5px;background:").append(AMBER)
                .append(";border-radius:5px;font-size:0;line-height:0;\">&nbsp;</div></td>")
                .append("<td valign=\"middle\" style=\"font-size:11px;font-weight:700;color:").append(INK)
                .append(";letter-spacing:1.4px;text-transform:uppercase;\">").append(title).append("</td></tr></table>")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("style=\"font-size:14px;color:#3A3A3A;margin-top:14px;\">");
    }

    private void closeSection(StringBuilder html) {
        html.append("</table></td></tr>");
    }

    private void row(StringBuilder html, String label, String value, String valueColor, boolean last) {
        String border = last ? "" : "border-bottom:1px solid " + HAIRLINE + ";";
        html.append("<tr><td style=\"padding:10px 0;").append(border).append("\">").append(label).append("</td>")
                .append("<td align=\"right\" style=\"padding:10px 0;").append(border)
                .append("font-weight:600;color:").append(valueColor).append(";\">").append(value)
                .append("</td></tr>");
    }

    private String deltaChip(ReportData data) {
        BigDecimal change = data.grossChangePercent();
        if (change == null) {
            return "";
        }
        boolean up = change.signum() >= 0;
        return "&nbsp;<span style=\"color:" + (up ? "#4ADE80" : "#F87171") + ";\">"
                + (up ? "&#9650; " : "&#9660; ") + change.abs().toPlainString() + "%</span>"
                + " <span style=\"color:#666;\">vs " + previousLabel(data) + "</span>";
    }

    private String previousLabel(ReportData data) {
        return switch (data.period()) {
            case DAILY -> "the day before";
            case WEEKLY -> "last week";
            case MONTHLY -> "the month before";
        };
    }

    private String periodLabel(ReportData data) {
        return switch (data.period()) {
            case DAILY -> LONG_DATE.format(data.range().startDate());
            case WEEKLY -> SHORT_DATE.format(data.range().startDate()) + " &ndash; "
                    + SHORT_DATE.format(data.range().endDate()) + " " + data.range().endDate().getYear();
            case MONTHLY -> MONTH_YEAR.format(data.range().startDate());
        };
    }

    private static boolean isMonthly(ReportData data) {
        return data.period() == ReportPeriod.MONTHLY;
    }

    private static int barWidth(BigDecimal value, BigDecimal peak) {
        if (peak == null || peak.signum() == 0 || value == null || value.signum() <= 0) {
            return 2;
        }
        int width = value.multiply(BigDecimal.valueOf(100))
                .divide(peak, 0, RoundingMode.HALF_UP)
                .intValue();
        return Math.max(2, Math.min(100, width));
    }

    private static String naira(BigDecimal amount) {
        return NAIRA + format(amount == null ? 0L : amount.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    private static String plainNaira(BigDecimal amount) {
        return "NGN " + format(amount == null ? 0L : amount.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    private static String percent(BigDecimal value) {
        return (value == null ? "0.0" : value.toPlainString()) + "%";
    }

    private static String format(long value) {
        return String.format(Locale.UK, "%,d", value);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
