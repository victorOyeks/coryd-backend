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


@Entity
@Table(name = "route_templates", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RouteTemplate extends BaseEntity {

    @Column(nullable = false)
    private String driverId;

    @Column(nullable = false)
    private String originLabel;
    @Column(nullable = false)
    private Double originLat;
    @Column(nullable = false)
    private Double originLng;

    @Column(nullable = false)
    private String destinationLabel;
    @Column(nullable = false)
    private Double destinationLat;
    @Column(nullable = false)
    private Double destinationLng;

    @Column(nullable = false)
    private Integer typicalSeatCount;
}
