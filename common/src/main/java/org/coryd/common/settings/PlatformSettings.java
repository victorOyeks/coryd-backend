package org.coryd.common.settings;

import lombok.extern.slf4j.Slf4j;
import org.coryd.common.kafka.events.PlatformSettingChangedEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The platform settings ops changed at runtime, in memory, in every service - one map lookup per read, no I/O. Fed by the
 * compacted platform-settings topic (PlatformSettingsKafkaListener, live) and a snapshot from trip-service
 * (PlatformSettingsBootstrap, at startup and every few minutes).
 *
 * <p>Every read names a fallback - the service's own property - which is used when ops never set the value, reset it, or
 * the value can't be read. So a service that never heard from trip-service behaves exactly as it did before settings existed.
 */
@Slf4j
@Component
public class PlatformSettings {

    private final Map<String, PlatformSettingChangedEvent> states = new ConcurrentHashMap<>();
    private final List<Consumer<PlatformSetting>> listeners = new CopyOnWriteArrayList<>();
    private volatile Clock clock = Clock.systemUTC();

    public void apply(PlatformSettingChangedEvent event) {
        if (event == null || event.getKey() == null) {
            return;
        }
        PlatformSetting setting = PlatformSetting.byKey(event.getKey()).orElse(null);
        if (setting == null) {
            log.debug("Ignoring unknown platform setting {}", event.getKey());
            return;
        }
        String before = raw(setting);
        PlatformSettingChangedEvent[] kept = new PlatformSettingChangedEvent[1];
        states.compute(event.getKey(), (key, old) -> {
            kept[0] = old == null || event.getVersion() >= old.getVersion() ? event : old;
            return kept[0];
        });
        if (kept[0] == event && !Objects.equals(before, raw(setting))) {
            log.info("Platform setting {} is now {}", setting.key(), raw(setting) == null ? "the service default" : raw(setting));
            listeners.forEach(listener -> {
                try {
                    listener.accept(setting);
                } catch (Exception e) {
                    log.error("Platform setting listener failed for {}: {}", setting.key(), e.getMessage(), e);
                }
            });
        }
    }

    /** Called with the setting whenever its effective value changes here. */
    public void onChange(Consumer<PlatformSetting> listener) {
        listeners.add(listener);
    }

    /** The value ops set and in effect now, or null when the service's own property applies. */
    public String raw(PlatformSetting setting) {
        PlatformSettingChangedEvent state = states.get(setting.key());
        if (state == null) {
            return null;
        }
        if (state.getScheduledFrom() != null && state.getScheduledValue() != null && !clock.instant().isBefore(state.getScheduledFrom())) {
            return state.getScheduledValue();
        }
        return state.getValue();
    }

    public int integer(PlatformSetting setting, int fallback) {
        String raw = raw(setting);
        if (raw == null) {
            return fallback;
        }
        try {
            return new BigDecimal(raw).intValueExact();
        } catch (Exception e) {
            log.warn("Platform setting {}='{}' unreadable - using {}", setting.key(), raw, fallback);
            return fallback;
        }
    }

    public BigDecimal decimal(PlatformSetting setting, BigDecimal fallback) {
        String raw = raw(setting);
        if (raw == null) {
            return fallback;
        }
        try {
            return new BigDecimal(raw);
        } catch (Exception e) {
            log.warn("Platform setting {}='{}' unreadable - using {}", setting.key(), raw, fallback);
            return fallback;
        }
    }

    public List<Integer> integers(PlatformSetting setting, List<Integer> fallback) {
        String raw = raw(setting);
        if (raw == null) {
            return fallback;
        }
        try {
            return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::valueOf).toList();
        } catch (Exception e) {
            log.warn("Platform setting {}='{}' unreadable - using {}", setting.key(), raw, fallback);
            return fallback;
        }
    }

    public Map<String, PlatformSettingChangedEvent> snapshot() {
        return Map.copyOf(states);
    }

    /** Tests only. */
    public void clear() {
        states.clear();
    }

    /** Tests only. */
    public void useClock(Clock clock) {
        this.clock = clock;
    }

    /** Null-safe reads for classes that may be built without Spring (unit tests construct properties by hand). */
    public static int integer(PlatformSettings settings, PlatformSetting setting, int fallback) {
        return settings == null ? fallback : settings.integer(setting, fallback);
    }

    public static BigDecimal decimal(PlatformSettings settings, PlatformSetting setting, BigDecimal fallback) {
        return settings == null ? fallback : settings.decimal(setting, fallback);
    }

    public static List<Integer> integers(PlatformSettings settings, PlatformSetting setting, List<Integer> fallback) {
        return settings == null ? fallback : settings.integers(setting, fallback);
    }

    public Instant now() {
        return clock.instant();
    }
}
