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

import java.time.Instant;

/** One change a driver made to their trip's departure - the trip's history and the driver's reliability. */
@Entity
@Table(name = "route_time_changes", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class RouteTimeChange extends BaseEntity {

    @Column(nullable = false, length = 36)
    private String routePostId;

    @Column(nullable = false, length = 36)
    private String driverId;

    @Column(nullable = false)
    private Instant previousDepartureTime;

    @Column(nullable = false)
    private Instant newDepartureTime;

    /** Paid riders on the trip when it changed - 0 means nobody was affected. */
    @Column(nullable = false)
    private int ridersBooked;

    /** Made with riders booked, close to departure - see TimeChangeProperties.lastMinuteMinutes. */
    @Column(nullable = false)
    private boolean lastMinute;
}
