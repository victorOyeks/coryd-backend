package org.coryd.notification.broadcast;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.response.PagedResponse;
import org.coryd.notification.config.WhatsAppProperties;
import org.coryd.notification.gateway.whatsapp.WhatsAppMessageKind;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Composing, previewing, cancelling and listing broadcasts (docs/ops-console/broadcasts.md). Nothing here sends - creating
 * one only records it as SCHEDULED; BroadcastSender sends it from its own thread, so no request waits on a large send.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastService {

    static final Duration MAX_AHEAD = Duration.ofDays(90);
    static final Duration PAST_GRACE = Duration.ofMinutes(5);
    /** Above this, the preview doesn't count opt-outs and devices person by person - it says so instead. */
    static final int PREVIEW_DETAIL_LIMIT = 20_000;
    public static final int SMS_MAX = 480;

    private final BroadcastRepository broadcasts;
    private final BroadcastRecipientRepository recipients;
    private final AudienceClient audienceClient;
    private final BroadcastLookups lookups;
    private final BroadcastProperties properties;
    private final WhatsAppProperties whatsAppProperties;
    private final BroadcastAuditWriter audit;
    private final ObjectMapper objectMapper;
    private Clock clock = Clock.systemUTC();

    /** Tests only. */
    public void useClock(Clock clock) {
        this.clock = clock;
    }

    public BroadcastDtos.Preview preview(BroadcastDtos.ComposeRequest request) {
        Validated v = validate(request, false);
        AudienceClient.Count count = count(v.audience());
        Integer optedOut = null;
        Map<String, Long> reachable = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>(v.warnings());
        if (count.total() <= PREVIEW_DETAIL_LIMIT) {
            int out = 0;
            long devices = 0;
            String after = null;
            do {
                AudienceClient.Page page = audienceClient.page(v.audience(), after, 1000);
                List<String> ids = page.members().stream().map(AudienceClient.Member::userId).toList();
                out += lookups.optedOut(ids).size();
                devices += lookups.withDevice(ids).size();
                after = page.nextAfter();
            } while (after != null);
            optedOut = out;
            reachable.put("PUSH", devices);
        } else {
            warnings.add("Over " + PREVIEW_DETAIL_LIMIT + " people - opt-outs and app installs are counted when it sends");
        }
        reachable.put("SMS", count.withPhone());
        reachable.put("EMAIL", count.withEmail());
        reachable.put("WHATSAPP", whatsappAvailable() ? count.withPhone() : 0);
        Integer willReceive = optedOut == null ? null : (int) count.total() - (v.urgent() ? 0 : optedOut);
        if (count.total() == 0) {
            warnings.add("Nobody is in this audience");
        }
        return new BroadcastDtos.Preview((int) count.total(), optedOut, willReceive,
                reachable.entrySet().stream().filter(e -> v.channels().contains(BroadcastChannel.valueOf(e.getKey())))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)),
                count.unmatched(), v.scheduledFor(), v.deferred(), smsSegments(smsText(v.title(), v.body())),
                sample(v.title(), v.body()), warnings);
    }

    @Transactional
    public BroadcastDtos.View create(BroadcastDtos.ComposeRequest request, OpsAdmin admin) {
        Validated v = validate(request, true);
        Instant now = clock.instant();
        long lastHour = broadcasts.countByCreatedAtAfterAndStatusNot(Instant.now().minus(Duration.ofHours(1)), Broadcast.Status.CANCELLED);
        if (lastHour >= properties.getMaxPerHour()) {
            throw new BadRequestException("Only " + properties.getMaxPerHour() + " broadcasts can be created in an hour - try again later");
        }
        AudienceClient.Count count = count(v.audience());
        if (count.total() == 0) {
            throw new BadRequestException("Nobody is in this audience" + (count.unmatched().isEmpty() ? "" : " - not found: "
                    + String.join(", ", count.unmatched())));
        }
        Broadcast b = broadcasts.save(Broadcast.builder()
                .title(v.title()).body(v.body())
                .channels(v.channels().stream().map(Enum::name).collect(Collectors.joining(",")))
                .audienceType(v.audience().type()).audienceActiveDays(v.audience().activeDays())
                .audienceIds(json(v.audience().ids())).audienceRefs(json(v.audience().refs()))
                .urgent(v.urgent()).status(Broadcast.Status.SCHEDULED)
                .requestedFor(v.requestedFor()).scheduledFor(v.scheduledFor()).deferredForQuietHours(v.deferred())
                .reason(v.reason()).createdBy(admin.id()).createdByName(admin.name())
                .recipientsTotal((int) count.total())
                .build());
        audit.write(admin.id(), admin.name(), v.scheduledFor().isAfter(now.plusSeconds(30)) ? "BROADCAST_SCHEDULED" : "BROADCAST_CREATED",
                "BROADCAST", b.getId(), null, Map.of("title", b.getTitle(), "channels", b.getChannels(), "audience", b.getAudienceType().name(),
                        "recipients", count.total(), "urgent", b.isUrgent(), "scheduledFor", b.getScheduledFor().toString()), v.reason());
        return view(b);
    }

    /** A scheduled broadcast never goes; one sending stops before its next recipient (those already sent stay sent). */
    @Transactional
    public BroadcastDtos.View cancel(String id, BroadcastDtos.CancelRequest request, OpsAdmin admin) {
        String reason = request == null || request.reason() == null || request.reason().isBlank() ? null : request.reason().trim();
        if (reason == null) {
            throw new BadRequestException("Give a reason for cancelling - it goes in the audit log");
        }
        Broadcast b = find(id);
        if (b.getStatus() != Broadcast.Status.SCHEDULED && b.getStatus() != Broadcast.Status.SENDING) {
            throw new BadRequestException("This broadcast is " + b.getStatus().name().toLowerCase() + " - only a scheduled or sending one can be cancelled");
        }
        Broadcast.Status before = b.getStatus();
        b.setStatus(Broadcast.Status.CANCELLED);
        b.setCancelledAt(clock.instant());
        b.setCancelledBy(admin.id());
        b.setCancelledByName(admin.name());
        b.setCancelReason(reason.length() > 500 ? reason.substring(0, 500) : reason);
        broadcasts.save(b);
        audit.write(admin.id(), admin.name(), "BROADCAST_CANCELLED", "BROADCAST", b.getId(), Map.of("status", before.name()),
                Map.of("status", "CANCELLED"), reason);
        return view(b);
    }

    @Transactional(readOnly = true)
    public PagedResponse<BroadcastDtos.View> list(List<Broadcast.Status> statuses, int page, int size) {
        PageRequest paging = PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 50)));
        return PagedResponse.from((statuses == null || statuses.isEmpty()
                ? broadcasts.findAllByOrderByCreatedAtDesc(paging)
                : broadcasts.findByStatusInOrderByCreatedAtDesc(statuses, paging)).map(this::view));
    }

    @Transactional(readOnly = true)
    public BroadcastDtos.View detail(String id) {
        return view(find(id));
    }

    @Transactional(readOnly = true)
    public PagedResponse<BroadcastDtos.RecipientView> recipients(String id, BroadcastRecipient.Status status, int page, int size) {
        find(id);
        PageRequest paging = PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 100)));
        return PagedResponse.from((status == null ? recipients.findByBroadcastIdOrderByUserId(id, paging)
                : recipients.findByBroadcastIdAndStatusOrderByUserId(id, status, paging))
                .map(r -> new BroadcastDtos.RecipientView(r.getUserId(), r.getRole(), r.getRef(), r.getStatus().name(), r.getSkipReason(),
                        r.getPush(), r.getSms(), r.getEmailOutcome(), r.getWhatsapp(), r.getError(), r.getSentAt())));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    record Validated(String title, String body, List<BroadcastChannel> channels, AudienceClient.Audience audience, boolean urgent,
                     Instant requestedFor, Instant scheduledFor, boolean deferred, String reason, List<String> warnings) {
    }

    Validated validate(BroadcastDtos.ComposeRequest r, boolean needReason) {
        if (r == null) {
            throw new BadRequestException("Send the message, its channels and audience");
        }
        String title = r.title() == null ? "" : r.title().trim();
        String body = r.body() == null ? "" : r.body().trim();
        if (title.isEmpty() || title.length() > 80) {
            throw new BadRequestException("The title needs 1 to 80 characters");
        }
        if (body.isEmpty() || body.length() > 1000) {
            throw new BadRequestException("The message needs 1 to 1000 characters");
        }
        if (r.channels() == null || r.channels().isEmpty() || r.channels().stream().anyMatch(java.util.Objects::isNull)) {
            throw new BadRequestException("Choose at least one channel: " + Arrays.toString(BroadcastChannel.values()));
        }
        List<BroadcastChannel> channels = r.channels().stream().distinct().toList();
        if (channels.contains(BroadcastChannel.WHATSAPP) && !whatsappAvailable()) {
            throw new BadRequestException("WhatsApp isn't available for broadcasts - it needs WhatsApp switched on and an approved broadcast "
                    + "template (WHATSAPP_TEMPLATE_BROADCAST)");
        }
        if (r.audience() == null || r.audience().type() == null) {
            throw new BadRequestException("Choose an audience: " + Arrays.toString(AudienceType.values()));
        }
        BroadcastDtos.AudienceSpec a = r.audience();
        if (a.type() == AudienceType.ACTIVE_RIDERS && (a.activeDays() == null || a.activeDays() < 1 || a.activeDays() > 365)) {
            throw new BadRequestException("Riders active in the last N days needs N between 1 and 365");
        }
        if (a.type() == AudienceType.USERS && (a.ids() == null || a.ids().isEmpty()) && (a.refs() == null || a.refs().isEmpty())) {
            throw new BadRequestException("List at least one user id or reference");
        }
        String reason = r.reason() == null ? "" : r.reason().trim();
        if (needReason && reason.isEmpty()) {
            throw new BadRequestException("Give a reason for the broadcast - it goes in the audit log");
        }
        if (reason.length() > 500) {
            throw new BadRequestException("Keep the reason under 500 characters");
        }
        boolean urgent = Boolean.TRUE.equals(r.urgent());
        Instant now = clock.instant();
        Instant requested = r.sendAt() == null ? now : r.sendAt();
        if (requested.isBefore(now.minus(PAST_GRACE))) {
            throw new BadRequestException("sendAt is in the past - leave it out to send now");
        }
        if (requested.isAfter(now.plus(MAX_AHEAD))) {
            throw new BadRequestException("A broadcast can be scheduled at most 90 days ahead");
        }
        if (requested.isBefore(now)) {
            requested = now;
        }
        Instant scheduled = urgent ? requested : properties.nextAllowed(requested);
        boolean deferred = scheduled.isAfter(requested);
        List<String> warnings = new ArrayList<>();
        if (deferred) {
            warnings.add("That's in quiet hours (" + properties.getQuietHoursStart() + "-" + properties.getQuietHoursEnd()
                    + " Lagos) - it will go at " + scheduled + ". Mark it urgent only for a service notice that can't wait.");
        }
        if (channels.contains(BroadcastChannel.SMS) && smsText(title, body).length() > SMS_MAX) {
            warnings.add("The SMS is cut to " + SMS_MAX + " characters");
        }
        AudienceClient.Audience audience = new AudienceClient.Audience(a.type(), a.type() == AudienceType.ACTIVE_RIDERS ? a.activeDays() : null,
                a.type() == AudienceType.USERS ? a.ids() : null, a.type() == AudienceType.USERS ? a.refs() : null);
        return new Validated(title, body, channels, audience, urgent, requested, scheduled, deferred, reason, warnings);
    }

    private AudienceClient.Count count(AudienceClient.Audience audience) {
        try {
            return audienceClient.count(audience);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            throw new BadRequestException(message(e));
        } catch (RestClientException e) {
            log.error("Audience count failed: {}", e.getMessage());
            throw new org.coryd.common.exception.ExternalServiceException("profile-service couldn't count the audience - try again shortly");
        }
    }

    private String message(org.springframework.web.client.HttpClientErrorException e) {
        try {
            var node = objectMapper.readTree(e.getResponseBodyAsString());
            return node.hasNonNull("message") ? node.get("message").asText() : "The audience was refused";
        } catch (Exception ignored) {
            return "The audience was refused";
        }
    }

    boolean whatsappAvailable() {
        return whatsAppProperties.isEnabled() && whatsAppProperties.templateId(WhatsAppMessageKind.BROADCAST) != null;
    }

    public static String smsText(String title, String body) {
        String text = "CoRyd: " + title + " - " + body;
        return text.length() > SMS_MAX ? text.substring(0, SMS_MAX - 1) + "…" : text;
    }

    static int smsSegments(String text) {
        boolean gsm = text.chars().allMatch(c -> c < 128);
        int single = gsm ? 160 : 70;
        int multi = gsm ? 153 : 67;
        return text.length() <= single ? 1 : (int) Math.ceil(text.length() / (double) multi);
    }

    static BroadcastDtos.Sample sample(String title, String body) {
        return new BroadcastDtos.Sample(title, body, smsText(title, body), title, body + "\n\n- The CoRyd team", title + ": " + body);
    }

    Broadcast find(String id) {
        return broadcasts.findById(id).orElseThrow(() -> new ResourceNotFoundException("Broadcast not found"));
    }

    BroadcastDtos.View view(Broadcast b) {
        return new BroadcastDtos.View(b.getId(), b.getTitle(), b.getBody(), Arrays.asList(b.getChannels().split(",")),
                new BroadcastDtos.AudienceSpec(b.getAudienceType(), b.getAudienceActiveDays(), list(b.getAudienceIds()), list(b.getAudienceRefs())),
                b.isUrgent(), b.getStatus().name(), b.getRequestedFor(), b.getScheduledFor(), b.isDeferredForQuietHours(), b.getReason(),
                b.getCreatedBy(), b.getCreatedByName(), b.getCreatedAt(), b.getCancelledBy(), b.getCancelledByName(), b.getCancelReason(),
                b.getCancelledAt(), b.getStartedAt(), b.getFinishedAt(), b.getLastError(), stats(b));
    }

    BroadcastDtos.Stats stats(Broadcast b) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (Object[] row : recipients.countByStatus(b.getId())) {
            byStatus.put(row[0].toString(), ((Number) row[1]).longValue());
        }
        Map<String, Map<String, Long>> byChannel = new LinkedHashMap<>();
        for (Object[] row : recipients.channelOutcomes(b.getId())) {
            byChannel.computeIfAbsent((String) row[0], k -> new LinkedHashMap<>()).put((String) row[1], ((Number) row[2]).longValue());
        }
        int resolved = byStatus.values().stream().mapToInt(Long::intValue).sum();
        return new BroadcastDtos.Stats(b.getAudienceResolvedAt() == null ? b.getRecipientsTotal() : resolved,
                n(byStatus, "SENT"), n(byStatus, "PARTIAL"), n(byStatus, "FAILED"), n(byStatus, "SKIPPED"), n(byStatus, "PENDING"), byChannel);
    }

    private static int n(Map<String, Long> m, String key) {
        return m.getOrDefault(key, 0L).intValue();
    }

    private String json(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    List<String> list(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }
}
