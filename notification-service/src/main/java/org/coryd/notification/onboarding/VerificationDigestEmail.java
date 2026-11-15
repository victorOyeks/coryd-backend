package org.coryd.notification.onboarding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The verification digest email: plain, clean HTML in the ops report's layout (dark CoRyd header band, white card, table
 * layout and inline styles only - what Gmail and Outlook both honour) and a plain-text part with the same content.
 */
@Component
public class VerificationDigestEmail {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final String INK = "#121212";
    private static final String AMBER = "#F5B301";
    private static final String MUTED = "#8A8A8A";
    private static final String HAIRLINE = "#F2F2F2";
    private static final String FONT = "-apple-system,'Segoe UI',Helvetica,Arial,sans-serif";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH).withZone(LAGOS);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH).withZone(LAGOS);
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH).withZone(LAGOS);

    private final String consoleUrl;
    private final String logoUrl;

    public VerificationDigestEmail(@Value("${coryd.ops.console-url:https://admin.coryd.ng}") String consoleUrl,
                                   @Value("${report.logo-url:}") String logoUrl) {
        this.consoleUrl = consoleUrl.endsWith("/") ? consoleUrl.substring(0, consoleUrl.length() - 1) : consoleUrl;
        this.logoUrl = logoUrl;
    }

    /** The console page for one row: the driver's verification review, or the person on the identity screen. */
    public String link(String kind, String userId) {
        String id = URLEncoder.encode(userId == null ? "" : userId, StandardCharsets.UTF_8);
        return "DRIVER_DOCUMENTS".equals(kind) ? consoleUrl + "/verification-queue/" + id : consoleUrl + "/identity-checks?person=" + id;
    }

    public String subject(VerificationDigestService.Counts counts, int highlighted, int highlightDays, Instant now) {
        return "Verification: " + counts.total() + " waiting"
                + (highlighted > 0 ? " (" + highlighted + " over " + days(highlightDays) + ")" : "") + " · " + DAY.format(now);
    }

    public String html(List<VerificationDigestService.Row> rows, VerificationDigestService.Counts counts, int highlighted,
                       int highlightDays, Instant now) {
        StringBuilder h = new StringBuilder(8192);
        h.append("<body style=\"margin:0;padding:24px 12px;background:#E9E9E9;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("style=\"max-width:640px;margin:0 auto;background:#FFFFFF;border-radius:14px;overflow:hidden;font-family:")
                .append(FONT).append(";\">");
        // header band, as the ops reports
        h.append("<tr><td style=\"background:").append(INK).append(";padding:28px 32px 26px;\">")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td valign=\"middle\">");
        if (logoUrl != null && !logoUrl.isBlank()) {
            h.append("<img src=\"").append(escape(logoUrl)).append("\" width=\"130\" alt=\"CoRyd\" style=\"display:block;border:0;\">");
        } else {
            h.append("<span style=\"font-size:24px;font-weight:700;color:#FFFFFF;letter-spacing:-0.8px;\">CoRyd</span>")
                    .append("<span style=\"color:").append(AMBER).append(";font-size:24px;font-weight:700;\">.</span>");
        }
        h.append("</td><td align=\"right\" valign=\"middle\" style=\"font-size:11px;color:#7A7A7A;letter-spacing:.9px;")
                .append("text-transform:uppercase;\">Verification digest</td></tr></table></td></tr>");
        // hero: the count
        h.append("<tr><td style=\"background:").append(INK).append(";padding:6px 32px 30px;\">")
                .append("<div style=\"font-size:11px;color:#7A7A7A;letter-spacing:1.2px;text-transform:uppercase;padding-bottom:10px;\">")
                .append(escape(LONG_DAY.format(now))).append("</div>")
                .append("<div style=\"font-size:40px;font-weight:700;color:#FFFFFF;letter-spacing:-1.4px;line-height:1.05;\">")
                .append(counts.total()).append(counts.total() == 1 ? " person waiting" : " people waiting").append("</div>")
                .append("<div style=\"font-size:13px;color:").append(highlighted > 0 ? AMBER : "#9A9A9A").append(";padding-top:8px;\">")
                .append(highlighted > 0 ? highlighted + " waiting more than " + days(highlightDays) : "Nobody has waited more than " + days(highlightDays))
                .append("</div></td></tr>");
        // counts
        h.append("<tr><td style=\"padding:24px 32px 0;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>");
        tile(h, counts.drivers(), "Driver documents");
        tile(h, counts.identityChecks(), "Identity checks");
        tile(h, counts.partialNameMatches(), "Partial name matches");
        h.append("</tr></table></td></tr>");
        // the list
        h.append("<tr><td style=\"padding:26px 32px 0;\">")
                .append("<div style=\"font-size:11px;font-weight:700;color:").append(INK)
                .append(";letter-spacing:1.4px;text-transform:uppercase;padding-bottom:10px;\">Oldest first</div>")
                .append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"font-size:13px;color:")
                .append(INK).append(";border-collapse:collapse;\">")
                .append("<tr style=\"font-size:11px;color:").append(MUTED).append(";text-transform:uppercase;letter-spacing:.6px;\">")
                .append("<td style=\"padding:6px 8px 6px 0;\">Person</td><td style=\"padding:6px 8px;\">Waiting for</td>")
                .append("<td style=\"padding:6px 8px;\">Submitted</td><td align=\"right\" style=\"padding:6px 0 6px 8px;\">Waiting</td></tr>");
        for (VerificationDigestService.Row row : rows) {
            String bg = row.highlighted() ? "background:#FFFBF2;" : "";
            h.append("<tr style=\"").append(bg).append("border-top:1px solid ").append(HAIRLINE).append(";\">")
                    .append("<td valign=\"top\" style=\"padding:10px 8px 10px ").append(row.highlighted() ? "8px" : "0").append(";\">")
                    .append("<a href=\"").append(escape(row.link())).append("\" style=\"color:").append(INK)
                    .append(";font-weight:600;text-decoration:underline;\">").append(escape(row.name() == null ? "(no name yet)" : row.name()))
                    .append("</a><div style=\"font-size:11px;color:").append(MUTED).append(";padding-top:2px;\">")
                    .append(escape(row.ref() == null ? "" : row.ref())).append(" · ").append(row.role() == null ? "" : role(row.role()))
                    .append("</div></td>")
                    .append("<td valign=\"top\" style=\"padding:10px 8px;\">").append(escape(row.what())).append("</td>")
                    .append("<td valign=\"top\" style=\"padding:10px 8px;white-space:nowrap;\">")
                    .append(row.submittedAt() == null ? "-" : escape(WHEN.format(row.submittedAt()))).append("</td>")
                    .append("<td valign=\"top\" align=\"right\" style=\"padding:10px 0 10px 8px;white-space:nowrap;")
                    .append(row.highlighted() ? "color:#8A6410;font-weight:700;" : "").append("\">")
                    .append(waiting(row.waiting())).append(row.highlighted() ? "<div style=\"font-size:10px;\">over " + days(highlightDays) + "</div>" : "")
                    .append("</td></tr>");
        }
        h.append("</table>");
        long more = counts.total() - rows.size();
        if (more > 0) {
            h.append("<div style=\"font-size:12px;color:").append(MUTED).append(";padding-top:10px;\">And ").append(more)
                    .append(" more - see the console.</div>");
        }
        h.append("</td></tr>");
        // footer
        h.append("<tr><td style=\"padding:28px 32px 32px;\"><div style=\"border-top:1px solid #F0F0F0;padding-top:18px;font-size:11px;")
                .append("color:#A8A8A8;line-height:1.7;\">Sent each morning while anyone is waiting for a verification officer. ")
                .append("Open the queue: <a href=\"").append(escape(consoleUrl)).append("/verification-queue\" style=\"color:#A8A8A8;\">")
                .append(escape(consoleUrl)).append("/verification-queue</a>. Time and recipients: Settings &rarr; Notifications.<br>")
                .append("Kinetum Global Solutions Ltd &middot; RC 9825799 &middot; Lagos, Nigeria</div></td></tr>");
        h.append("</table></body>");
        return h.toString();
    }

    public String text(List<VerificationDigestService.Row> rows, VerificationDigestService.Counts counts, int highlighted,
                       int highlightDays, Instant now) {
        StringBuilder t = new StringBuilder(2048);
        t.append("CoRyd verification digest - ").append(LONG_DAY.format(now)).append("\n\n")
                .append(counts.total()).append(counts.total() == 1 ? " person waiting" : " people waiting");
        if (highlighted > 0) {
            t.append(", ").append(highlighted).append(" waiting more than ").append(days(highlightDays));
        }
        t.append("\nDriver documents: ").append(counts.drivers()).append(" · Identity checks: ").append(counts.identityChecks())
                .append(" · Partial name matches: ").append(counts.partialNameMatches()).append("\n\nOldest first:\n");
        for (VerificationDigestService.Row row : rows) {
            t.append("\n").append(row.highlighted() ? "! " : "- ").append(row.name() == null ? "(no name yet)" : row.name())
                    .append(" (").append(row.ref() == null ? "" : row.ref()).append(", ").append(row.role() == null ? "" : role(row.role()))
                    .append(")\n  ").append(row.what())
                    .append("\n  Submitted ").append(row.submittedAt() == null ? "-" : WHEN.format(row.submittedAt()))
                    .append(" · waiting ").append(waiting(row.waiting()))
                    .append(row.highlighted() ? " (over " + days(highlightDays) + ")" : "")
                    .append("\n  ").append(row.link()).append("\n");
        }
        long more = counts.total() - rows.size();
        if (more > 0) {
            t.append("\nAnd ").append(more).append(" more - see the console.\n");
        }
        t.append("\nSent each morning while anyone is waiting for a verification officer.\n").append(consoleUrl).append("/verification-queue\n");
        return t.toString();
    }

    private static void tile(StringBuilder h, long value, String label) {
        h.append("<td width=\"33%\" valign=\"top\" style=\"padding-right:8px;\"><div style=\"background:#F7F7F7;border-radius:10px;")
                .append("padding:14px 16px;\"><div style=\"font-size:24px;font-weight:700;color:").append(INK).append(";\">").append(value)
                .append("</div><div style=\"font-size:11px;color:").append(MUTED).append(";padding-top:4px;\">").append(label)
                .append("</div></div></td>");
    }

    /** "3d 4h", "5h 20m", "12m". */
    static String waiting(Duration d) {
        if (d == null) {
            return "-";
        }
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return Math.max(0, minutes) + "m";
    }

    private static String days(int n) {
        return n == 1 ? "1 day" : n + " days";
    }

    private static String role(String role) {
        return "DRIVER".equals(role) ? "Driver" : "Rider";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
