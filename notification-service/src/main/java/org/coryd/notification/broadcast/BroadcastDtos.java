package org.coryd.notification.broadcast;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Broadcast contract - docs/ops-console/broadcasts.md. */
public final class BroadcastDtos {

    private BroadcastDtos() {
    }

    public record AudienceSpec(AudienceType type, Integer activeDays, List<String> ids, List<String> refs) {
    }

    /** sendAt: omit to send now. urgent: ignores quiet hours and announcement opt-outs - service notices only. */
    public record ComposeRequest(String title, String body, List<BroadcastChannel> channels, AudienceSpec audience, Boolean urgent,
                                 Instant sendAt, String reason) {
    }

    public record CancelRequest(String reason) {
    }

    /**
     * recipients: everyone in the audience now. optedOut: of those, turned announcements off (left out unless urgent).
     * willReceive: recipients - optedOut (or all, when urgent). reachable: how many each channel can reach.
     */
    public record Preview(int recipients, Integer optedOut, Integer willReceive, Map<String, Long> reachable, List<String> unmatched,
                          Instant scheduledFor, boolean deferredForQuietHours, int smsSegments, Sample sample, List<String> warnings) {
    }

    public record Sample(String pushTitle, String pushBody, String sms, String emailSubject, String emailBody, String whatsapp) {
    }

    public record Stats(int recipients, int sent, int partial, int failed, int skipped, int pending,
                        Map<String, Map<String, Long>> byChannel) {
    }

    public record View(String id, String title, String body, List<String> channels, AudienceSpec audience, boolean urgent, String status,
                       Instant requestedFor, Instant scheduledFor, boolean deferredForQuietHours, String reason, String createdBy,
                       String createdByName, Instant createdAt, String cancelledBy, String cancelledByName, String cancelReason,
                       Instant cancelledAt, Instant startedAt, Instant finishedAt, String lastError, Stats stats) {
    }

    public record RecipientView(String userId, String role, String ref, String status, String skipReason, String push, String sms,
                                String email, String whatsapp, String error, Instant sentAt) {
    }
}
