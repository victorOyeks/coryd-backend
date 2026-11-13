package org.coryd.trip.settings;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.exception.BadRequestException;
import org.coryd.common.exception.ResourceNotFoundException;
import org.coryd.common.kafka.KafkaTopics;
import org.coryd.common.kafka.events.PlatformSettingChangedEvent;
import org.coryd.common.response.PagedResponse;
import org.coryd.common.settings.PlatformSetting;
import org.coryd.common.settings.PlatformSettings;
import org.coryd.common.settings.PlatformSettingsSource;
import org.coryd.trip.entity.OpsAuditLog;
import org.coryd.trip.entity.PlatformSettingChange;
import org.coryd.trip.entity.PlatformSettingValue;
import org.coryd.trip.repository.PlatformSettingChangeRepository;
import org.coryd.trip.repository.PlatformSettingValueRepository;
import org.coryd.trip.service.OpsAuditWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The owner of the platform settings (docs/ops-console/settings.md): validates and stores what ops sets, keeps the history,
 * writes every change to the ops audit log (who, which setting, before -> after, why), applies scheduled changes when their
 * time comes, and publishes each setting's state on the compacted coryd-platform-settings topic once the change commits -
 * every service follows that topic (common PlatformSettings) and switches within seconds.
 *
 * <p>Also the PlatformSettingsSource for trip-service's own PlatformSettings: it reads this table rather than its own API.
 */
@Slf4j
@Service
public class PlatformSettingsService implements PlatformSettingsSource {

    /** An effectiveFrom this close to now (or earlier) means "now". */
    static final Duration IMMEDIATE_WITHIN = Duration.ofSeconds(30);
    static final Duration MAX_AHEAD = Duration.ofDays(366);
    public static final String SYSTEM = "system";

    private final PlatformSettingValueRepository values;
    private final PlatformSettingChangeRepository changes;
    private final PlatformSettingDefaults defaults;
    private final OpsAuditWriter audit;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final PlatformSettings platformSettings;
    private final TransactionTemplate tx;
    private volatile Clock clock = Clock.systemUTC();

    @Autowired
    public PlatformSettingsService(PlatformSettingValueRepository values, PlatformSettingChangeRepository changes,
                                   PlatformSettingDefaults defaults, OpsAuditWriter audit, KafkaTemplate<String, Object> kafkaTemplate,
                                   @Autowired(required = false) PlatformSettings platformSettings,
                                   PlatformTransactionManager transactionManager) {
        this.values = values;
        this.changes = changes;
        this.defaults = defaults;
        this.audit = audit;
        this.kafkaTemplate = kafkaTemplate;
        this.platformSettings = platformSettings;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Tests only. */
    public void useClock(Clock clock) {
        this.clock = clock;
    }

    // ---- reads ---------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SettingsDtos.SettingsView list() {
        Map<String, PlatformSettingValue> rows = new LinkedHashMap<>();
        values.findAll().forEach(v -> rows.put(v.getKey(), v));
        Map<PlatformSetting.Group, List<SettingsDtos.Setting>> grouped = new LinkedHashMap<>();
        for (PlatformSetting setting : PlatformSetting.values()) {
            grouped.computeIfAbsent(setting.group(), g -> new ArrayList<>()).add(view(setting, rows.get(setting.key())));
        }
        List<SettingsDtos.Group> groups = new ArrayList<>();
        grouped.forEach((group, settings) -> groups.add(new SettingsDtos.Group(group.name(), settings)));
        return new SettingsDtos.SettingsView(clock.instant(), "Africa/Lagos", groups);
    }

    @Transactional(readOnly = true)
    public SettingsDtos.Setting get(String key) {
        PlatformSetting setting = setting(key);
        return view(setting, values.findById(key).orElse(null));
    }

    @Transactional(readOnly = true)
    public PagedResponse<SettingsDtos.Change> history(String key, int page, int size) {
        PageRequest paging = PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 100)));
        if (key == null || key.isBlank()) {
            return PagedResponse.from(changes.findAllByOrderByCreatedAtDesc(paging).map(SettingsDtos_change()));
        }
        setting(key);
        return PagedResponse.from(changes.findByKeyOrderByCreatedAtDesc(key, paging).map(SettingsDtos_change()));
    }

    // ---- changes -------------------------------------------------------------------------------------------------

    /** Set now, or schedule for effectiveFrom. Every refusal is a 400 with a message ops can act on. */
    public SettingsDtos.UpdateResult update(String key, SettingsDtos.UpdateRequest request, OpsAdmin admin) {
        PlatformSetting setting = setting(key);
        if (request == null) {
            throw new BadRequestException("Send the new value and a reason");
        }
        String reason = reason(request.reason());
        String value;
        try {
            value = setting.normalize(request.value());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        Instant now = clock.instant();
        Instant effectiveFrom = request.effectiveFrom();
        boolean immediate = effectiveFrom == null || !effectiveFrom.isAfter(now.plus(IMMEDIATE_WITHIN));
        if (!immediate && effectiveFrom.isAfter(now.plus(MAX_AHEAD))) {
            throw new BadRequestException("A change can be scheduled at most a year ahead");
        }
        return inTransaction(() -> {
            PlatformSettingValue row = lockedRow(setting, now);
            if (row.getScheduledFrom() != null) {
                throw new BadRequestException(setting.label() + " already has a change to " + row.getScheduledValue()
                        + " scheduled for " + row.getScheduledFrom() + " - cancel it first");
            }
            String before = effective(setting, row);
            crossCheck(setting, value);
            PlatformSettingChange change;
            if (immediate) {
                if (value.equals(before)) {
                    throw new BadRequestException(setting.label() + " is already " + value + " " + setting.unit());
                }
                String oldValue = row.getValue();
                row.setValue(value);
                touch(row, admin, now);
                change = history(setting, PlatformSettingChange.Action.SET, oldValue, value, now, admin, reason);
                audit.write(admin.id(), admin.name(), OpsAuditLog.Action.SETTING_CHANGED, OpsAuditLog.TargetType.SETTING, setting.key(),
                        null, Map.of("value", before), Map.of("value", value, "effectiveFrom", now.toString()), reason);
            } else {
                row.setScheduledValue(value);
                row.setScheduledFrom(effectiveFrom);
                touch(row, admin, now);
                change = history(setting, PlatformSettingChange.Action.SCHEDULED, before, value, effectiveFrom, admin, reason);
                row.setScheduledChangeId(change.getId());
                audit.write(admin.id(), admin.name(), OpsAuditLog.Action.SETTING_SCHEDULED, OpsAuditLog.TargetType.SETTING,
                        setting.key(), null, Map.of("value", before),
                        Map.of("value", value, "effectiveFrom", effectiveFrom.toString()), reason);
            }
            values.save(row);
            publishAfterCommit(row);
            List<String> warnings = new ArrayList<>();
            if (setting.legal()) {
                warnings.add(setting.label() + " is in the published Terms of Service - update coryd.ng/terms to match"
                        + (immediate ? ", and consider scheduling such changes ahead next time" : " before " + effectiveFrom));
            }
            return new SettingsDtos.UpdateResult(view(setting, row), SettingsDtos_change().apply(change), warnings);
        });
    }

    public SettingsDtos.UpdateResult cancelScheduled(String key, SettingsDtos.ReasonRequest request, OpsAdmin admin) {
        PlatformSetting setting = setting(key);
        String reason = reason(request == null ? null : request.reason());
        Instant now = clock.instant();
        return inTransaction(() -> {
            PlatformSettingValue row = values.findForUpdate(setting.key())
                    .filter(r -> r.getScheduledFrom() != null)
                    .orElseThrow(() -> new BadRequestException(setting.label() + " has no scheduled change to cancel"));
            if (!row.getScheduledFrom().isAfter(now)) {
                throw new BadRequestException("That change has already taken effect - set the value again instead");
            }
            String scheduled = row.getScheduledValue();
            Instant from = row.getScheduledFrom();
            row.setScheduledValue(null);
            row.setScheduledFrom(null);
            row.setScheduledChangeId(null);
            touch(row, admin, now);
            values.save(row);
            PlatformSettingChange change = history(setting, PlatformSettingChange.Action.SCHEDULE_CANCELLED, scheduled, null, from, admin, reason);
            audit.write(admin.id(), admin.name(), OpsAuditLog.Action.SETTING_SCHEDULE_CANCELLED, OpsAuditLog.TargetType.SETTING,
                    setting.key(), null, Map.of("scheduledValue", scheduled, "effectiveFrom", from.toString()), Map.of(), reason);
            publishAfterCommit(row);
            return new SettingsDtos.UpdateResult(view(setting, row), SettingsDtos_change().apply(change), List.of());
        });
    }

    /** Back to the services' own default (the env var), straight away. */
    public SettingsDtos.UpdateResult reset(String key, SettingsDtos.ReasonRequest request, OpsAdmin admin) {
        PlatformSetting setting = setting(key);
        String reason = reason(request == null ? null : request.reason());
        Instant now = clock.instant();
        return inTransaction(() -> {
            PlatformSettingValue row = values.findForUpdate(setting.key()).orElse(null);
            if (row == null || row.getValue() == null) {
                throw new BadRequestException(setting.label() + " is already on its default");
            }
            if (row.getScheduledFrom() != null) {
                throw new BadRequestException(setting.label() + " has a scheduled change - cancel it first");
            }
            String before = row.getValue();
            row.setValue(null);
            touch(row, admin, now);
            values.save(row);
            PlatformSettingChange change = history(setting, PlatformSettingChange.Action.RESET, before, defaults.of(setting), now, admin, reason);
            audit.write(admin.id(), admin.name(), OpsAuditLog.Action.SETTING_RESET, OpsAuditLog.TargetType.SETTING, setting.key(),
                    null, Map.of("value", before), Map.of("value", defaults.of(setting), "default", true), reason);
            publishAfterCommit(row);
            return new SettingsDtos.UpdateResult(view(setting, row), SettingsDtos_change().apply(change), List.of());
        });
    }

    /**
     * Applies every scheduled change whose time has come. Every service already switched at effectiveFrom (the published
     * record carries it); this makes it the stored value, records it and republishes. Each setting in its own transaction.
     */
    @Scheduled(fixedDelayString = "${coryd.platform-settings.apply-check-ms:15000}", initialDelayString = "${coryd.platform-settings.apply-check-ms:15000}")
    public int applyDue() {
        int applied = 0;
        for (String key : values.findDueKeys(clock.instant())) {
            try {
                if (Boolean.TRUE.equals(inTransaction(() -> applyOne(key)))) {
                    applied++;
                }
            } catch (Exception e) {
                log.error("Couldn't apply the scheduled change to {}: {}", key, e.getMessage(), e);
            }
        }
        return applied;
    }

    private Boolean applyOne(String key) {
        Instant now = clock.instant();
        PlatformSettingValue row = values.findForUpdate(key).orElse(null);
        if (row == null || row.getScheduledFrom() == null || row.getScheduledFrom().isAfter(now)) {
            return false;
        }
        PlatformSetting setting = PlatformSetting.byKey(key).orElse(null);
        if (setting == null) {
            return false;
        }
        PlatformSettingChange scheduled = row.getScheduledChangeId() == null ? null : changes.findById(row.getScheduledChangeId()).orElse(null);
        String before = row.getValue() == null ? defaults.of(setting) : row.getValue();
        String value = row.getScheduledValue();
        Instant from = row.getScheduledFrom();
        row.setValue(value);
        row.setScheduledValue(null);
        row.setScheduledFrom(null);
        row.setScheduledChangeId(null);
        row.setVersion(row.getVersion() + 1);
        row.setUpdatedAt(now);
        values.save(row);
        String by = scheduled == null ? "unknown admin" : scheduled.getActorName();
        String reason = "Scheduled by " + by + (scheduled == null ? "" : ": " + scheduled.getReason());
        changes.save(PlatformSettingChange.builder().key(key).action(PlatformSettingChange.Action.APPLIED).oldValue(before)
                .newValue(value).effectiveFrom(from).actorId(SYSTEM).actorName("Scheduled change").reason(truncate(reason)).build());
        audit.write(SYSTEM, "Scheduled change", OpsAuditLog.Action.SETTING_APPLIED, OpsAuditLog.TargetType.SETTING, key, null,
                Map.of("value", before), Map.of("value", value, "effectiveFrom", from.toString()), reason);
        publishAfterCommit(row);
        log.info("Scheduled change applied: {} {} -> {} (effective {})", key, before, value, from);
        return true;
    }

    // ---- the feed ------------------------------------------------------------------------------------------------

    /** Every stored setting, as published - the snapshot other services bootstrap from (GET /internal/settings). */
    @Override
    @Transactional(readOnly = true)
    public List<PlatformSettingChangedEvent> fetchAll() {
        return values.findAll().stream().map(PlatformSettingsService::event).toList();
    }

    /** Republishes everything at startup, so the compacted topic always holds every setting's latest state. */
    @EventListener(ApplicationReadyEvent.class)
    public void publishAll() {
        try {
            for (PlatformSettingChangedEvent event : fetchAll()) {
                kafkaTemplate.send(KafkaTopics.PLATFORM_SETTINGS, event.getKey(), event);
            }
        } catch (Exception e) {
            log.warn("Couldn't republish the platform settings at startup - services still bootstrap from GET /internal/settings: {}",
                    e.getMessage());
        }
    }

    static PlatformSettingChangedEvent event(PlatformSettingValue row) {
        return PlatformSettingChangedEvent.builder().key(row.getKey()).value(row.getValue()).scheduledValue(row.getScheduledValue())
                .scheduledFrom(row.getScheduledFrom()).version(row.getVersion()).changedAt(row.getUpdatedAt()).build();
    }

    private void publishAfterCommit(PlatformSettingValue row) {
        PlatformSettingChangedEvent event = event(row);
        OpsAuditWriter.afterCommit(() -> {
            if (platformSettings != null) {
                platformSettings.apply(event);
            }
            try {
                kafkaTemplate.send(KafkaTopics.PLATFORM_SETTINGS, event.getKey(), event);
            } catch (Exception e) {
                log.error("Couldn't publish {} - other services pick it up from the snapshot within minutes: {}", event.getKey(), e.getMessage());
            }
        });
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private PlatformSettingValue lockedRow(PlatformSetting setting, Instant now) {
        return values.findForUpdate(setting.key()).orElseGet(() -> {
            try {
                values.saveAndFlush(PlatformSettingValue.builder().key(setting.key()).version(0).createdAt(now).updatedAt(now).build());
            } catch (DataIntegrityViolationException raced) {
                // another admin created it first - lock theirs
            }
            return values.findForUpdate(setting.key()).orElseThrow();
        });
    }

    private void touch(PlatformSettingValue row, OpsAdmin admin, Instant now) {
        row.setVersion(row.getVersion() + 1);
        row.setUpdatedBy(admin.id());
        row.setUpdatedByName(admin.name());
        row.setUpdatedAt(now);
    }

    private PlatformSettingChange history(PlatformSetting setting, PlatformSettingChange.Action action, String oldValue, String newValue,
                                          Instant effectiveFrom, OpsAdmin admin, String reason) {
        return changes.save(PlatformSettingChange.builder().key(setting.key()).action(action).oldValue(oldValue).newValue(newValue)
                .effectiveFrom(effectiveFrom).actorId(admin.id()).actorName(admin.name()).reason(reason).build());
    }

    /** The final document reminder must come after (fewer days before expiry than) the first. */
    private void crossCheck(PlatformSetting setting, String value) {
        if (setting == PlatformSetting.DOCUMENT_FINAL_REMINDER_DAYS || setting == PlatformSetting.DOCUMENT_REMINDER_DAYS) {
            int first = setting == PlatformSetting.DOCUMENT_REMINDER_DAYS ? Integer.parseInt(value)
                    : Integer.parseInt(effective(PlatformSetting.DOCUMENT_REMINDER_DAYS, values.findById(PlatformSetting.DOCUMENT_REMINDER_DAYS.key()).orElse(null)));
            int last = setting == PlatformSetting.DOCUMENT_FINAL_REMINDER_DAYS ? Integer.parseInt(value)
                    : Integer.parseInt(effective(PlatformSetting.DOCUMENT_FINAL_REMINDER_DAYS, values.findById(PlatformSetting.DOCUMENT_FINAL_REMINDER_DAYS.key()).orElse(null)));
            if (last >= first) {
                throw new BadRequestException("The final document reminder (" + last + " days) must be fewer days before expiry than the first ("
                        + first + " days)");
            }
        }
    }

    private String effective(PlatformSetting setting, PlatformSettingValue row) {
        if (row != null && row.getScheduledFrom() != null && row.getScheduledValue() != null && !clock.instant().isBefore(row.getScheduledFrom())) {
            return row.getScheduledValue();
        }
        return row != null && row.getValue() != null ? row.getValue() : defaults.of(setting);
    }

    private SettingsDtos.Setting view(PlatformSetting setting, PlatformSettingValue row) {
        SettingsDtos.Scheduled scheduled = null;
        if (row != null && row.getScheduledFrom() != null) {
            PlatformSettingChange origin = row.getScheduledChangeId() == null ? null : changes.findById(row.getScheduledChangeId()).orElse(null);
            scheduled = new SettingsDtos.Scheduled(row.getScheduledValue(), row.getScheduledFrom(),
                    origin == null ? row.getUpdatedBy() : origin.getActorId(), origin == null ? row.getUpdatedByName() : origin.getActorName(),
                    origin == null ? null : origin.getReason());
        }
        SettingsDtos.Change last = changes.findByKeyOrderByCreatedAtDesc(setting.key(), PageRequest.of(0, 1)).stream()
                .findFirst().map(SettingsDtos_change()).orElse(null);
        String value = row == null ? null : row.getValue();
        return new SettingsDtos.Setting(setting.key(), setting.label(), setting.description(), setting.group().name(), setting.type().name(),
                setting.unit(), setting.min(), setting.max(), setting.legal(), setting.usedBy(), setting.source(), defaults.of(setting),
                value, effective(setting, row), value != null, scheduled, last, row == null ? 0 : row.getVersion());
    }

    private static java.util.function.Function<PlatformSettingChange, SettingsDtos.Change> SettingsDtos_change() {
        return c -> new SettingsDtos.Change(c.getId(), c.getKey(), c.getAction().name(), c.getOldValue(), c.getNewValue(),
                c.getEffectiveFrom(), c.getActorId(), c.getActorName(), c.getReason(), c.getCreatedAt());
    }

    static PlatformSetting setting(String key) {
        return PlatformSetting.byKey(key).orElseThrow(() -> new ResourceNotFoundException("No setting called '" + key + "'. Known: "
                + String.join(", ", Arrays.stream(PlatformSetting.values()).map(PlatformSetting::key).toList())));
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException("Give a reason for the change - it goes in the audit log");
        }
        if (reason.trim().length() > 500) {
            throw new BadRequestException("Keep the reason under 500 characters");
        }
        return reason.trim();
    }

    private static String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

}
