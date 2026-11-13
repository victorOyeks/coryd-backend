package org.coryd.notification.broadcast;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.enums.NotificationChannel;
import org.coryd.notification.entity.DeviceToken;
import org.coryd.notification.entity.NotificationLog;
import org.coryd.notification.entity.UserNotification;
import org.coryd.notification.gateway.EmailService;
import org.coryd.notification.gateway.FcmService;
import org.coryd.notification.gateway.TermiiSmsService;
import org.coryd.notification.gateway.whatsapp.WhatsAppMessage;
import org.coryd.notification.gateway.whatsapp.WhatsAppMessageKind;
import org.coryd.notification.gateway.whatsapp.WhatsAppSendResult;
import org.coryd.notification.gateway.whatsapp.WhatsAppService;
import org.coryd.notification.repository.DeviceTokenRepository;
import org.coryd.notification.repository.NotificationLogRepository;
import org.coryd.notification.repository.UserNotificationRepository;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends due broadcasts, one at a time, on its own thread - never a request thread, and never the shared scheduler thread
 * (a long send would hold up the report jobs). For each: resolve the audience into recipients once (announcement opt-outs
 * skipped unless urgent), then send recipient by recipient at broadcast.rate-per-second through the same gateways as every
 * other notification, recording each channel's outcome. Checks for a cancellation before every recipient (one primary-key read); a non-urgent
 * broadcast still going when quiet hours start pauses and resumes when they end.
 */
@Slf4j
@Component
public class BroadcastSender implements DisposableBean {

    public static final String EVENT_TYPE = "BROADCAST";
    static final int BATCH = 100;

    private final BroadcastRepository broadcasts;
    private final BroadcastRecipientRepository recipients;
    private final AudienceClient audienceClient;
    private final BroadcastLookups lookups;
    private final BroadcastProperties properties;
    private final DeviceTokenRepository deviceTokens;
    private final UserNotificationRepository feed;
    private final NotificationLogRepository logs;
    private final FcmService fcm;
    private final TermiiSmsService sms;
    private final EmailService email;
    private final WhatsAppService whatsApp;
    private final TransactionTemplate tx;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "broadcast-sender");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private Clock clock = Clock.systemUTC();
    private SendPacerFactory pacerFactory = SendPacer::new;

    interface SendPacerFactory {
        SendPacer create(int ratePerSecond);
    }

    public BroadcastSender(BroadcastRepository broadcasts, BroadcastRecipientRepository recipients, AudienceClient audienceClient,
                           BroadcastLookups lookups, BroadcastProperties properties, DeviceTokenRepository deviceTokens,
                           UserNotificationRepository feed, NotificationLogRepository logs, FcmService fcm, TermiiSmsService sms,
                           EmailService email, WhatsAppService whatsApp, PlatformTransactionManager transactionManager) {
        this.broadcasts = broadcasts;
        this.recipients = recipients;
        this.audienceClient = audienceClient;
        this.lookups = lookups;
        this.properties = properties;
        this.deviceTokens = deviceTokens;
        this.feed = feed;
        this.logs = logs;
        this.fcm = fcm;
        this.sms = sms;
        this.email = email;
        this.whatsApp = whatsApp;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Tests only. */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    /** Tests only. */
    void usePacer(SendPacerFactory factory) {
        this.pacerFactory = factory;
    }

    /** Hands the next due broadcast to the sender thread, unless it's still busy with one. */
    @Scheduled(fixedDelayString = "${broadcast.check-ms:5000}", initialDelayString = "${broadcast.check-ms:5000}")
    public void dispatchDue() {
        if (busy.get()) {
            return;
        }
        for (String id : broadcasts.findDue(clock.instant(), PageRequest.of(0, 1))) {
            if (busy.compareAndSet(false, true)) {
                worker.execute(() -> {
                    try {
                        sendNow(id);
                    } finally {
                        busy.set(false);
                    }
                });
            }
        }
    }

    /** Claims and sends one broadcast on the calling thread. Public for tests; false when it wasn't due or was taken. */
    public boolean sendNow(String id) {
        Integer claimed = tx.execute(s -> broadcasts.claim(id, clock.instant()));
        if (claimed == null || claimed == 0) {
            return false;
        }
        Broadcast b = broadcasts.findById(id).orElseThrow();
        try {
            if (b.getAudienceResolvedAt() == null) {
                resolve(b);
            }
            Outcome outcome = send(b);
            tx.executeWithoutResult(s -> finish(id, outcome));
        } catch (Exception e) {
            log.error("Broadcast {} failed: {}", id, e.getMessage(), e);
            tx.executeWithoutResult(s -> broadcasts.findById(id).ifPresent(x -> {
                if (x.getStatus() == Broadcast.Status.SENDING) {
                    x.setStatus(Broadcast.Status.FAILED);
                    x.setFinishedAt(clock.instant());
                }
                x.setLastError(truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 500));
                broadcasts.save(x);
            }));
        }
        return true;
    }

    enum Outcome { DONE, CANCELLED, PAUSED }

    /** Pages the audience into PENDING recipients - or SKIPPED, for announcement opt-outs on a non-urgent broadcast. */
    void resolve(Broadcast b) {
        AudienceClient.Audience audience = new AudienceClient.Audience(b.getAudienceType(), b.getAudienceActiveDays(),
                list(b.getAudienceIds()), list(b.getAudienceRefs()));
        String after = null;
        int total = 0;
        do {
            AudienceClient.Page page = audienceClient.page(audience, after, 500);
            List<String> ids = page.members().stream().map(AudienceClient.Member::userId).toList();
            Set<String> optedOut = b.isUrgent() ? Set.of() : lookups.optedOut(ids);
            for (AudienceClient.Member m : page.members()) {
                if (recipients.existsByBroadcastIdAndUserId(b.getId(), m.userId())) {
                    continue;
                }
                boolean skip = optedOut.contains(m.userId());
                try {
                    recipients.save(BroadcastRecipient.builder().broadcastId(b.getId()).userId(m.userId()).role(m.role()).ref(m.ref())
                            .phone(m.phone()).email(m.email())
                            .status(skip ? BroadcastRecipient.Status.SKIPPED : BroadcastRecipient.Status.PENDING)
                            .skipReason(skip ? "Turned announcements off" : null).build());
                } catch (DataIntegrityViolationException duplicate) {
                    // already there (a resolve resumed after a restart)
                }
            }
            total += page.members().size();
            after = page.nextAfter();
        } while (after != null);
        int resolvedTotal = total;
        tx.executeWithoutResult(s -> broadcasts.findById(b.getId()).ifPresent(x -> {
            x.setAudienceResolvedAt(clock.instant());
            x.setRecipientsTotal(resolvedTotal);
            broadcasts.save(x);
        }));
        b.setAudienceResolvedAt(clock.instant());
    }

    Outcome send(Broadcast b) throws InterruptedException {
        List<BroadcastChannel> channels = Arrays.stream(b.getChannels().split(",")).map(BroadcastChannel::valueOf).toList();
        SendPacer pacer = pacerFactory.create(properties.getRatePerSecond());
        while (true) {
            List<BroadcastRecipient> batch = recipients.batch(b.getId(), BroadcastRecipient.Status.PENDING,
                    PageRequest.of(0, BATCH));
            if (batch.isEmpty()) {
                return Outcome.DONE;
            }
            Set<String> whatsappOff = channels.contains(BroadcastChannel.WHATSAPP)
                    ? lookups.whatsappOff(batch.stream().map(BroadcastRecipient::getUserId).toList()) : Set.of();
            for (BroadcastRecipient r : batch) {
                if (broadcasts.statusOf(b.getId()) == Broadcast.Status.CANCELLED) {
                    return Outcome.CANCELLED;
                }
                if (!b.isUrgent() && properties.isQuiet(clock.instant())) {
                    return pause(b);
                }
                pacer.acquire();
                deliver(b, r, channels, whatsappOff.contains(r.getUserId()));
                recipients.save(r);
            }
        }
    }

    private Outcome pause(Broadcast b) {
        Instant resume = properties.nextAllowed(clock.instant());
        tx.executeWithoutResult(s -> broadcasts.findById(b.getId()).ifPresent(x -> {
            if (x.getStatus() == Broadcast.Status.SENDING) {
                x.setStatus(Broadcast.Status.SCHEDULED);
                x.setScheduledFor(resume);
                x.setDeferredForQuietHours(true);
                broadcasts.save(x);
            }
        }));
        log.info("Broadcast {} paused for quiet hours - resumes at {}", b.getId(), resume);
        return Outcome.PAUSED;
    }

    /** Every chosen channel for one person; the recipient's status sums them up. */
    void deliver(Broadcast b, BroadcastRecipient r, List<BroadcastChannel> channels, boolean whatsappOff) {
        List<String> errors = new ArrayList<>();
        int sent = 0;
        int failed = 0;
        for (BroadcastChannel channel : channels) {
            String outcome;
            try {
                outcome = switch (channel) {
                    case PUSH -> push(b, r);
                    case SMS -> sms(b, r);
                    case EMAIL -> email(b, r);
                    case WHATSAPP -> whatsapp(b, r, whatsappOff);
                };
            } catch (Exception e) {
                outcome = "FAILED";
                errors.add(channel + ": " + e.getMessage());
            }
            switch (channel) {
                case PUSH -> r.setPush(outcome);
                case SMS -> r.setSms(outcome);
                case EMAIL -> r.setEmailOutcome(outcome);
                case WHATSAPP -> r.setWhatsapp(outcome);
            }
            if ("SENT".equals(outcome)) {
                sent++;
            } else if ("FAILED".equals(outcome)) {
                failed++;
            }
        }
        r.setSentAt(clock.instant());
        r.setError(errors.isEmpty() ? null : truncate(String.join("; ", errors), 300));
        if (sent > 0) {
            r.setStatus(failed > 0 ? BroadcastRecipient.Status.PARTIAL : BroadcastRecipient.Status.SENT);
        } else if (failed > 0) {
            r.setStatus(BroadcastRecipient.Status.FAILED);
        } else {
            r.setStatus(BroadcastRecipient.Status.SKIPPED);
            r.setSkipReason("No way to reach them on the chosen channels");
        }
    }

    private String push(Broadcast b, BroadcastRecipient r) {
        feed.save(UserNotification.builder().userId(r.getUserId()).title(b.getTitle()).body(b.getBody()).eventType(EVENT_TYPE).build());
        List<DeviceToken> tokens = deviceTokens.findByUserId(r.getUserId());
        if (tokens.isEmpty()) {
            return "NO_DEVICE";
        }
        boolean any = false;
        for (DeviceToken token : tokens) {
            FcmService.PushResult result = fcm.sendPush(r.getUserId(), token.getFcmToken(), b.getTitle(), b.getBody(), EVENT_TYPE, b.getId(), null);
            logs.save(NotificationLog.builder().userId(r.getUserId()).channel(NotificationChannel.PUSH).eventType(EVENT_TYPE)
                    .delivered(result.delivered()).providerMessageId(result.messageId()).errorCode(result.errorCode()).build());
            any |= result.delivered();
        }
        return any ? "SENT" : "FAILED";
    }

    private String sms(Broadcast b, BroadcastRecipient r) {
        if (blank(r.getPhone())) {
            return "NO_PHONE";
        }
        boolean ok = sms.send(r.getPhone(), BroadcastService.smsText(b.getTitle(), b.getBody()));
        logs.save(NotificationLog.builder().recipientPhone(r.getPhone()).userId(r.getUserId()).channel(NotificationChannel.SMS)
                .eventType(EVENT_TYPE).delivered(ok).build());
        return ok ? "SENT" : "FAILED";
    }

    private String email(Broadcast b, BroadcastRecipient r) {
        if (blank(r.getEmail())) {
            return "NO_EMAIL";
        }
        boolean ok = email.send(r.getEmail(), b.getTitle(), b.getBody() + "\n\n- The CoRyd team");
        logs.save(NotificationLog.builder().recipientEmail(r.getEmail()).userId(r.getUserId()).channel(NotificationChannel.EMAIL)
                .eventType(EVENT_TYPE).delivered(ok).build());
        return ok ? "SENT" : "FAILED";
    }

    private String whatsapp(Broadcast b, BroadcastRecipient r, boolean off) {
        if (blank(r.getPhone())) {
            return "NO_PHONE";
        }
        if (off) {
            return "OPTED_OUT";
        }
        Optional<WhatsAppSendResult> result = whatsApp.send(r.getPhone(),
                new WhatsAppMessage(WhatsAppMessageKind.BROADCAST, Map.of("title", b.getTitle(), "message", b.getBody())));
        if (result.isEmpty()) {
            return "UNAVAILABLE";
        }
        logs.save(NotificationLog.builder().recipientPhone(r.getPhone()).userId(r.getUserId()).channel(NotificationChannel.WHATSAPP)
                .eventType(EVENT_TYPE).delivered(result.get().delivered()).providerMessageId(result.get().providerMessageId())
                .errorCode(result.get().errorCode()).build());
        return result.get().delivered() ? "SENT" : "FAILED";
    }

    private void finish(String id, Outcome outcome) {
        Broadcast x = broadcasts.findById(id).orElseThrow();
        if (outcome == Outcome.CANCELLED || x.getStatus() == Broadcast.Status.CANCELLED) {
            recipients.batch(id, BroadcastRecipient.Status.PENDING, PageRequest.of(0, Integer.MAX_VALUE))
                    .forEach(r -> {
                        r.setStatus(BroadcastRecipient.Status.SKIPPED);
                        r.setSkipReason("Broadcast cancelled");
                        recipients.save(r);
                    });
        }
        int sent = 0, failed = 0, skipped = 0, total = 0;
        for (Object[] row : recipients.countByStatus(id)) {
            int n = ((Number) row[1]).intValue();
            total += n;
            switch (BroadcastRecipient.Status.valueOf(row[0].toString())) {
                case SENT, PARTIAL -> sent += n;
                case FAILED -> failed += n;
                case SKIPPED -> skipped += n;
                default -> {
                }
            }
        }
        x.setRecipientsTotal(total);
        x.setRecipientsSent(sent);
        x.setRecipientsFailed(failed);
        x.setRecipientsSkipped(skipped);
        if (outcome == Outcome.DONE && x.getStatus() == Broadcast.Status.SENDING) {
            x.setStatus(Broadcast.Status.SENT);
        }
        if (outcome != Outcome.PAUSED) {
            x.setFinishedAt(clock.instant());
        }
        broadcasts.save(x);
        log.info("Broadcast {} {}: {} sent, {} failed, {} skipped of {}", id, x.getStatus(), sent, failed, skipped, total);
    }

    private List<String> list(String json) {
        if (json == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public void destroy() {
        worker.shutdownNow();
    }
}
