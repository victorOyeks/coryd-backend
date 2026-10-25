package org.coryd.notification.entity;

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

/** An NDPC tier email that went out - kept so each one is sent once, never daily. */
@Entity
@Table(name = "ndpc_threshold_alerts", schema = "notification")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class NdpcThresholdAlert extends BaseEntity {

    public enum Kind { APPROACHING, REACHED }

    /** The tier's threshold (200, 1000, 5000) - identifies the tier even if its name is reworded. */
    @Column(nullable = false)
    private long threshold;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    /** How many people we held data about when it went out. */
    @Column(nullable = false)
    private long dataSubjects;
}
