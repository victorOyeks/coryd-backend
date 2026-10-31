package org.coryd.trip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

import java.time.Instant;

/**
 * A rider's "Notify me when a driver is heading this way". One-off: desiredDepartureTime, expiring a day
 * later. Repeating: daysMask (Mon=1 ... Sun=64) and timeMinutes (Lagos time) instead, until expiresAt.
 */
@Entity
@Table(name = "notify_me_requests", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class NotifyMeRequest extends BaseEntity {

    @Column(nullable = false)
    private String riderId;

    @Column(nullable = false)
    private Double originLat;
    @Column(nullable = false)
    private Double originLng;
    @Column(nullable = false)
    private Double destinationLat;
    @Column(nullable = false)
    private Double destinationLng;

    private String originLabel;
    private String destinationLabel;

    private Instant desiredDepartureTime;

    private Integer daysMask;

    private Integer timeMinutes;

    /** Repeating only: a ride at any time on its days counts, not only around timeMinutes. */
    @Builder.Default
    @Column(nullable = false)
    private boolean anyTimeOfDay = false;

    @Column(nullable = false)
    private Instant expiresAt;

    @Builder.Default
    @Column(nullable = false)
    private boolean fulfilled = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean cancelled = false;

    public boolean isRepeating() {
        return daysMask != null;
    }
}
