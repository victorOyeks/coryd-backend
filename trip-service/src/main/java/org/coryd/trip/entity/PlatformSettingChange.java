package org.coryd.trip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

import java.time.Instant;

/** One change to a platform setting - the history the console shows under each setting. */
@Entity
@Table(name = "platform_setting_changes", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformSettingChange extends BaseEntity {

    public enum Action {
        /** Set, taking effect straight away. */
        SET,
        /** Set to take effect at effectiveFrom. */
        SCHEDULED,
        /** A scheduled change took effect. */
        APPLIED,
        /** A scheduled change was called off before its time. */
        SCHEDULE_CANCELLED,
        /** Back to the services' own defaults. */
        RESET
    }

    @Column(name = "setting_key", nullable = false, length = 36)
    private String key;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    @Column(length = 200)
    private String oldValue;

    @Column(length = 200)
    private String newValue;

    private Instant effectiveFrom;

    @Column(nullable = false, length = 36)
    private String actorId;

    @Column(length = 120)
    private String actorName;

    @Column(nullable = false, length = 500)
    private String reason;
}
