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

@Entity
@Table(name = "sos_incidents", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SosIncident extends BaseEntity {

    /** Null for a driver's SOS raised on the route itself. */
    private String bookingId;

    private String routePostId;

    @Column(nullable = false)
    private String triggeredBy;

    /** RIDER or DRIVER. */
    private String triggeredByRole;

    private Double latitude;
    private Double longitude;

    private Instant opsNotifiedAt;
}
