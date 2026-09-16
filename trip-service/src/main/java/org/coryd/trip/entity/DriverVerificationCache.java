package org.coryd.trip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


/**
 * Local read-side cache of profile-service's verification decision (D-104's gate),
 * kept in sync via DriverApprovedEvent/DriverRejectedEvent so posting a route
 * doesn't require a synchronous cross-service call on the hot path.
 */
@Entity
@Table(name = "driver_verification_cache", schema = "trip")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DriverVerificationCache {

    @Id
    private String driverId;

    @Column(nullable = false)
    private boolean approved;

    /** First successful route post while unverified - the start of the grace window. */
    private Instant firstRouteAt;

    public DriverVerificationCache(String driverId, boolean approved) {
        this.driverId = driverId;
        this.approved = approved;
    }
}
