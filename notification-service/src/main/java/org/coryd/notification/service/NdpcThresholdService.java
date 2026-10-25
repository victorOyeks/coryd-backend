package org.coryd.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.notification.client.PlatformStatsClient;
import org.coryd.notification.config.NdpcThresholdProperties;
import org.coryd.notification.entity.NdpcThresholdAlert;
import org.coryd.notification.gateway.EmailService;
import org.coryd.notification.repository.NdpcThresholdAlertRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Warns ops before CoRyd outgrows its NDPC registration. Counts everyone we hold personal data about
 * (accounts + waitlist-only sign-ups, all time - it only grows, so a tier is never crossed unnoticed) and,
 * per tier, emails the report recipients once when the count nears it and once when it reaches it.
 * An email that fails to send isn't recorded, so the next run tries again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NdpcThresholdService {

    private static final NumberFormat COUNT = NumberFormat.getIntegerInstance(Locale.ENGLISH);

    private final NdpcThresholdProperties properties;
    private final NdpcThresholdAlertRepository alertRepository;
    private final PlatformStatsClient statsClient;
    private final EmailService emailService;

    @Value("${report.recipients:}")
    private List<String> recipients;

    record Due(NdpcThresholdProperties.Tier tier, NdpcThresholdAlert.Kind kind) {
    }

    @Transactional
    public void check() {
        if (!properties.isEnabled()) {
            return;
        }
        PlatformStatsClient.DataSubjects count = statsClient.dataSubjects();
        if (count == null) {
            log.warn("NDPC threshold check skipped - identity-service didn't return the data-subject count");
            return;
        }
        List<Due> due = due(count.total());
        if (due.isEmpty()) {
            return;
        }
        List<String> to = recipients == null ? List.of() : recipients.stream().filter(Objects::nonNull).map(String::trim)
                .filter(a -> !a.isEmpty()).distinct().toList();
        if (to.isEmpty()) {
            log.error("NDPC: {} people's data held and {} tier email(s) due, but REPORT_RECIPIENTS is empty - nobody told",
                    count.total(), due.size());
            return;
        }
        if (!emailService.sendHtml(to, subject(due, count), html(due, count))) {
            log.error("NDPC tier email didn't send - will try again on the next run");
            return;
        }
        due.forEach(d -> alertRepository.save(NdpcThresholdAlert.builder()
                .threshold(d.tier().getThreshold()).kind(d.kind()).dataSubjects(count.total()).build()));
        log.info("NDPC tier email sent to {} recipient(s): {} people's data held, {}", to.size(), count.total(),
                due.stream().map(d -> d.kind() + " " + d.tier().getName()).toList());
    }

    /** What's newly owed per tier: "reached" once the count is at the threshold, else "approaching" past the warn point. */
    List<Due> due(long total) {
        List<Due> due = new ArrayList<>();
        properties.getTiers().stream().sorted(Comparator.comparingLong(NdpcThresholdProperties.Tier::getThreshold)).forEach(tier -> {
            boolean reachedSent = alertRepository.existsByThresholdAndKind(tier.getThreshold(), NdpcThresholdAlert.Kind.REACHED);
            if (total >= tier.getThreshold()) {
                if (!reachedSent) {
                    due.add(new Due(tier, NdpcThresholdAlert.Kind.REACHED));
                }
            } else if (total >= tier.warnAt(properties.getWarnPercent()) && !reachedSent
                    && !alertRepository.existsByThresholdAndKind(tier.getThreshold(), NdpcThresholdAlert.Kind.APPROACHING)) {
                due.add(new Due(tier, NdpcThresholdAlert.Kind.APPROACHING));
            }
        });
        return due;
    }

    /** Named after the most pressing item: the highest tier reached, else the one being approached. */
    String subject(List<Due> due, PlatformStatsClient.DataSubjects count) {
        Due top = due.stream().filter(d -> d.kind() == NdpcThresholdAlert.Kind.REACHED)
                .max(Comparator.comparingLong(d -> d.tier().getThreshold()))
                .orElse(due.get(due.size() - 1));
        return top.kind() == NdpcThresholdAlert.Kind.REACHED
                ? "NDPC: CoRyd now holds data on " + COUNT.format(count.total()) + " people - " + top.tier().getName() + " tier reached"
                : "NDPC: CoRyd is nearing the " + top.tier().getName() + " tier (" + COUNT.format(count.total()) + " of "
                        + COUNT.format(top.tier().getThreshold()) + " people)";
    }

    String html(List<Due> due, PlatformStatsClient.DataSubjects count) {
        StringBuilder items = new StringBuilder();
        long lowest = properties.getTiers().stream().mapToLong(NdpcThresholdProperties.Tier::getThreshold).min().orElse(0);
        for (Due d : due) {
            NdpcThresholdProperties.Tier tier = d.tier();
            String action = tier.getThreshold() == lowest ? "register CoRyd with the NDPC" : "upgrade CoRyd's NDPC registration";
            items.append("<li style=\"margin-bottom:10px\">");
            if (d.kind() == NdpcThresholdAlert.Kind.REACHED) {
                items.append("<b>").append(tier.getName()).append(" reached</b> - over ").append(COUNT.format(tier.getThreshold()))
                        .append(" people. Please ").append(action).append(" at the <b>").append(tier.getName())
                        .append("</b> tier now (").append(tier.getYearlyFee()).append(" a year).");
            } else {
                items.append("<b>").append(tier.getName()).append(" approaching</b> - ").append(COUNT.format(count.total()))
                        .append(" of ").append(COUNT.format(tier.getThreshold())).append(" people. Plan to ").append(action)
                        .append(" at the <b>").append(tier.getName()).append("</b> tier (").append(tier.getYearlyFee())
                        .append(" a year) before it's reached.");
            }
            items.append("</li>");
        }
        return "<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#1f2937;max-width:600px\">"
                + "<h2 style=\"font-size:18px\">NDPC registration check</h2>"
                + "<p>CoRyd now holds personal data on <b>" + COUNT.format(count.total()) + " people</b> ("
                + COUNT.format(count.registeredUsers()) + " registered accounts + " + COUNT.format(count.waitlistOnly())
                + " beta waitlist sign-ups who haven't joined yet).</p>"
                + "<ul style=\"padding-left:18px\">" + items + "</ul>"
                + "<p>Register or upgrade at <a href=\"https://services.ndpc.gov.ng\">services.ndpc.gov.ng</a>. "
                + "Under the Nigeria Data Protection Act 2023, data controllers of major importance must register "
                + "at the tier that matches how many people's data they handle.</p>"
                + "<p style=\"color:#6b7280;font-size:12px\">Counted all time, so the number only grows and a tier is "
                + "never crossed unnoticed. You'll get one email per tier when it's near and one when it's reached.</p>"
                + "</div>";
    }
}
