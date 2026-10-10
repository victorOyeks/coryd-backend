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

/** "Where do you usually drive?" - and whether the driver wants demand pushes at all. */
@Entity
@Table(name = "driver_driving_preferences", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DriverDrivingPreference extends BaseEntity {

    @Column(nullable = false, unique = true, length = 36)
    private String driverId;

    private Double startLat;
    private Double startLng;
    private String startLabel;

    private Double endLat;
    private Double endLng;
    private String endLabel;

    @Builder.Default
    @Column(nullable = false)
    private boolean mornings = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean evenings = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean demandAlerts = true;
}
