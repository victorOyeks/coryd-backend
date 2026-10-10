package org.coryd.trip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

import java.time.LocalDate;

/** A demand push sent to a driver on a given day - at most one a day, a few a week. */
@Entity
@Table(name = "demand_alert_log", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DemandAlertLog extends BaseEntity {

    @Column(nullable = false, length = 36)
    private String driverId;

    @Column(nullable = false)
    private LocalDate sentOn;

    @Column(nullable = false)
    private Integer routeCount;
}
