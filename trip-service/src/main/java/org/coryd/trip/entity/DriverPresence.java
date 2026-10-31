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
import java.time.LocalDate;

/**
 * Where a driver last opened the app - one row per driver, overwritten, so there is never a trail. Cleared once
 * stale. Also holds when they were last told riders nearby are going their way, so they aren't told too often.
 */
@Entity
@Table(name = "driver_presence", schema = "trip")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DriverPresence {

    @Id
    @Column(length = 36)
    private String driverId;

    private Double lat;
    private Double lng;

    /** When the app last sent the location - null once cleared. */
    private Instant seenAt;

    private Instant lastNearbyAlertAt;
    private LocalDate alertDay;

    @Builder.Default
    @Column(nullable = false)
    private int alertsThatDay = 0;
}
