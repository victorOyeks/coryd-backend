package org.coryd.trip.settings;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** GET/PUT /internal/ops/settings - the contract is in docs/ops-console/settings.md. */
public final class SettingsDtos {

    private SettingsDtos() {
    }

    public record SettingsView(Instant at, String timezone, List<Group> groups) {
    }

    public record Group(String group, List<Setting> settings) {
    }

    /**
     * value: what ops set (null = default in force). effectiveValue: what the services use now. scheduled: a change waiting
     * for its time. lastChange: the latest history row.
     */
    public record Setting(String key, String label, String description, String group, String type, String unit,
                          BigDecimal min, BigDecimal max, boolean legal, String usedBy, String defaultSource,
                          String defaultValue, String value, String effectiveValue, boolean overridden, Scheduled scheduled,
                          Change lastChange, long version) {
    }

    public record Scheduled(String value, Instant effectiveFrom, String scheduledBy, String scheduledByName, String reason) {
    }

    public record Change(String id, String key, String action, String oldValue, String newValue, Instant effectiveFrom,
                         String actorId, String actorName, String reason, Instant at) {
    }

    /** effectiveFrom: omit (or now/past) to apply at once; a future instant schedules it. */
    public record UpdateRequest(String value, String reason, Instant effectiveFrom) {
    }

    public record ReasonRequest(String reason) {
    }

    /** The setting after the change, and anything ops should know (a Terms-of-Service setting applied straight away). */
    public record UpdateResult(Setting setting, Change change, List<String> warnings) {
    }
}
