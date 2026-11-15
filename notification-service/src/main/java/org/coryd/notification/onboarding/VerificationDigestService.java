package org.coryd.notification.onboarding;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.NotificationChannel;
import org.coryd.notification.broadcast.BroadcastAuditWriter;
import org.coryd.notification.entity.NotificationLog;
import org.coryd.notification.gateway.EmailService;
import org.coryd.notification.repository.NotificationLogRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The verification officers' morning digest (docs/ops-console/onboarding-reminders.md): when anyone has submitted and is
 * waiting on an officer - drivers in the verification queue, identity checks in review, partial name matches nobody has
 * looked at - one email listing them, oldest first, those waiting too long highlighted, each linked to the console. Sent
 * to every active VERIFICATION or SUPER_ADMIN admin with an email, plus the extra addresses in the setting, once each.
 * Nothing is sent when nobody is waiting. A dry run builds it and sends nothing.
 */
@Slf4j
@Service
public class VerificationDigestService {

    public static final String EVENT_TYPE = "VERIFICATION_DIGEST";
    public static final String JOB = "verification-digest";
    private static final Set<String> OFFICER_ROLES = Set.of("VERIFICATION", "SUPER_ADMIN");

    /** One person waiting, as the email lists them. link: the console page to open. */
    public record Row(String kind, String userId, String role, String ref, String name, String what, Instant submittedAt,
                      Instant waitingSince, @com.fasterxml.jackson.annotation.JsonIgnore Duration waiting, boolean highlighted, String link) {

        /** How long they have waited, in whole minutes (null when unknown). */
        @com.fasterxml.jackson.annotation.JsonProperty("waitingMinutes")
        public Long waitingMinutes() {
            return waiting == null ? null : waiting.toMinutes();
        }
    }

    public record Counts(long drivers, long identityChecks, long partialNameMatches, long total) {
    }

    /**
     * One run. sent: an email went to at least one recipient. delivered: recipients the mail server took it for.
     * notSentBecause: why nothing was sent (null when it was). rows: what the email lists (or would).
     */
    public record Run(boolean dryRun, Instant at, Counts counts, int highlighted, int highlightDays, List<String> recipients,
                      boolean sent, int delivered, String notSentBecause, String subject, List<Row> rows) {
    }

    private final OnboardingDataClient client;
    private final NotificationJobSettings settings;
    private final VerificationDigestEmail email;
    private final EmailService emailService;
    private final NotificationLogRepository logs;
    private final BroadcastAuditWriter audit;
    private volatile Clock clock = Clock.systemUTC();

    public VerificationDigestService(OnboardingDataClient client, NotificationJobSettings settings, VerificationDigestEmail email,
                                     EmailService emailService, NotificationLogRepository logs, BroadcastAuditWriter audit) {
        this.client = client;
        this.settings = settings;
        this.email = email;
        this.emailService = emailService;
        this.logs = logs;
        this.audit = audit;
    }

    /** Tests only. */
    public void useClock(Clock clock) {
        this.clock = clock;
    }

    public Run run(boolean dryRun, JobActor actor) {
        Instant now = clock.instant();
        int highlightDays = settings.highlightDays();
        OnboardingDataClient.Waiting waiting = client.verificationWaiting();
        List<Row> rows = rows(waiting.items(), now, highlightDays);
        Counts counts = new Counts(waiting.drivers(), waiting.identityChecks(), waiting.partialNameMatches(),
                waiting.drivers() + waiting.identityChecks() + waiting.partialNameMatches());
        int highlighted = (int) rows.stream().filter(Row::highlighted).count();
        List<String> recipients = recipients(client.opsAdmins(), settings.extraRecipients());

        String subject = rows.isEmpty() ? null : email.subject(counts, highlighted, highlightDays, now);
        String notSent = rows.isEmpty() ? "Nobody is waiting for a verification officer"
                : recipients.isEmpty() ? "No active Verification or Super admin has an email, and no extra addresses are set" : null;
        int delivered = 0;
        if (!dryRun && notSent == null) {
            String html = email.html(rows, counts, highlighted, highlightDays, now);
            String text = email.text(rows, counts, highlighted, highlightDays, now);
            for (String to : recipients) {
                boolean ok = emailService.sendHtmlWithText(to, subject, html, text);
                logs.save(NotificationLog.builder().recipientEmail(to).channel(NotificationChannel.EMAIL).eventType(EVENT_TYPE)
                        .delivered(ok).build());
                if (ok) {
                    delivered++;
                }
            }
        }
        boolean sent = !dryRun && notSent == null;
        Run run = new Run(dryRun, now, counts, highlighted, highlightDays, recipients, sent, delivered, notSent, subject, rows);
        if (!dryRun && (sent || !actor.system())) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("waiting", counts.total());
            after.put("listed", rows.size());
            after.put("highlighted", highlighted);
            after.put("recipients", recipients.size());
            after.put("delivered", delivered);
            if (notSent != null) {
                after.put("notSentBecause", notSent);
            }
            audit.write(actor.id(), actor.name(), "VERIFICATION_DIGEST_SENT", "NOTIFICATION_JOB", JOB, null, after,
                    actor.system() ? "Daily verification digest" : "Run by hand from the ops console");
        }
        log.info("Verification digest{}: {} waiting, {} highlighted, {} recipients, sent={}", dryRun ? " (dry run)" : "", counts.total(),
                highlighted, recipients.size(), sent);
        return run;
    }

    /** Oldest wait first (no time last); highlighted when waiting more than highlightDays days. */
    List<Row> rows(List<OnboardingDataClient.WaitingItem> items, Instant now, int highlightDays) {
        List<Row> rows = new ArrayList<>();
        for (OnboardingDataClient.WaitingItem item : items == null ? List.<OnboardingDataClient.WaitingItem>of() : items) {
            Instant since = item.waitingSince() != null ? item.waitingSince() : item.submittedAt();
            Duration waiting = since == null ? null : Duration.between(since, now).isNegative() ? Duration.ZERO : Duration.between(since, now);
            boolean highlighted = waiting != null && waiting.compareTo(Duration.ofDays(highlightDays)) > 0;
            rows.add(new Row(item.kind(), item.userId(), item.role(), item.ref(), item.name(), item.what(), item.submittedAt(), since,
                    waiting, highlighted, email.link(item.kind(), item.userId())));
        }
        rows.sort(Comparator.comparing(Row::waitingSince, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> r.ref() == null ? "" : r.ref()));
        return rows;
    }

    /** Active VERIFICATION and SUPER_ADMIN admins with an email, then the extra addresses - lower case, each once. */
    static List<String> recipients(List<OnboardingDataClient.OpsAdmin> admins, List<String> extra) {
        Set<String> out = new LinkedHashSet<>();
        for (OnboardingDataClient.OpsAdmin admin : admins == null ? List.<OnboardingDataClient.OpsAdmin>of() : admins) {
            if ("ACTIVE".equals(admin.status()) && OFFICER_ROLES.contains(admin.opsRole() == null ? "SUPER_ADMIN" : admin.opsRole())
                    && admin.email() != null && !admin.email().isBlank()) {
                out.add(admin.email().trim().toLowerCase(Locale.ROOT));
            }
        }
        for (String address : extra == null ? List.<String>of() : extra) {
            if (address != null && !address.isBlank()) {
                out.add(address.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(out);
    }
}
