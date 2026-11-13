package org.coryd.trip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A platform setting ops has set (docs/ops-console/settings.md) - see PlatformSettingsService. */
@Entity
@Table(name = "platform_settings", schema = "trip")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformSettingValue {

    @Id
    @Column(name = "setting_key", length = 36)
    private String key;

    /** Null: the services' own defaults apply. */
    @Column(length = 200)
    private String value;

    @Column(length = 200)
    private String scheduledValue;

    private Instant scheduledFrom;

    /** The SCHEDULED history row a pending change came from - who scheduled it and why. */
    @Column(length = 36)
    private String scheduledChangeId;

    @Column(nullable = false)
    private long version;

    @Column(length = 36)
    private String updatedBy;

    @Column(length = 120)
    private String updatedByName;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;
}
