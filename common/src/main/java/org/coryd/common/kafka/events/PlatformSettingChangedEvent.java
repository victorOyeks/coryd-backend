package org.coryd.common.kafka.events;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One platform setting as trip-service now has it - published on the compacted topic {@code coryd-platform-settings},
 * keyed by the setting key, so every service replays the latest of each at startup and follows changes live.
 *
 * <p>{@code value} is what ops set (null: never set, or reset - the service uses its own property). A scheduled change
 * rides along: from {@code scheduledFrom} on, {@code scheduledValue} is the value, even before trip-service's job has
 * promoted it - so every service switches at the same moment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformSettingChangedEvent {

    private String key;
    private String value;
    private String scheduledValue;
    private Instant scheduledFrom;
    /** Bumped on every change - an older record never overwrites a newer one, whichever feed it arrived on. */
    private long version;
    private Instant changedAt;
}
