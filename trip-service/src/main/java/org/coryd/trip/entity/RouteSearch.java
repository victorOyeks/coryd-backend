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

/** One rider search, kept for demand totals only - never shown or acted on per rider. */
@Entity
@Table(name = "route_searches", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RouteSearch extends BaseEntity {

    @Column(nullable = false, length = 36)
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

    @Column(nullable = false, length = 30)
    private String originCell;
    @Column(nullable = false, length = 30)
    private String destinationCell;

    /** The time asked for, minutes after midnight, Lagos time. */
    @Column(nullable = false)
    private Integer localMinutes;

    @Column(nullable = false)
    private boolean weekend;

    @Column(nullable = false)
    private Integer resultCount;
}
