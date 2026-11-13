package org.coryd.ops.platform;

import org.coryd.common.exception.BadRequestException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request bodies the console sends for phase 6 - checked here first so an obviously bad one never leaves ops-service. */
public final class PlatformRequests {

    private PlatformRequests() {
    }

    /** effectiveFrom: leave out to apply now; a future instant schedules it (PRD Q5). */
    public record SettingUpdate(String value, String reason, Instant effectiveFrom) {
        public SettingUpdate checked() {
            if (value == null || value.isBlank()) {
                throw new BadRequestException("Send the new value");
            }
            return this.withReason();
        }

        private SettingUpdate withReason() {
            requireReason(reason);
            return this;
        }
    }

    public record Reason(String reason) {
        public Reason checked() {
            requireReason(reason);
            return this;
        }
    }

    public record Audience(String type, Integer activeDays, List<String> ids, List<String> refs) {
    }

    public record Broadcast(String title, String body, List<String> channels, Audience audience, Boolean urgent, Instant sendAt,
                            String reason) {
        public Broadcast checked(boolean needReason) {
            if (title == null || title.isBlank()) {
                throw new BadRequestException("The title needs 1 to 80 characters");
            }
            if (body == null || body.isBlank()) {
                throw new BadRequestException("The message needs 1 to 1000 characters");
            }
            if (channels == null || channels.isEmpty()) {
                throw new BadRequestException("Choose at least one channel: PUSH, SMS, EMAIL, WHATSAPP");
            }
            if (audience == null || audience.type() == null) {
                throw new BadRequestException("Choose an audience");
            }
            if (needReason) {
                requireReason(reason);
            }
            return this;
        }
    }

    static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Give a reason - it goes in the audit log");
        }
    }

    static Map<String, Object> reason(String reason) {
        return Map.of("reason", reason);
    }
}
