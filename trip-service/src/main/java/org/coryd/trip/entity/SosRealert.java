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

/** One "still unacknowledged" re-alert sent to ops for an SOS-queue incident. */
@Entity
@Table(name = "sos_realerts", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SosRealert extends BaseEntity {

    @Column(nullable = false, length = 36)
    private String incidentId;

    @Column(nullable = false)
    private int level;

    @Column(nullable = false)
    private int minutesOpen;
}
