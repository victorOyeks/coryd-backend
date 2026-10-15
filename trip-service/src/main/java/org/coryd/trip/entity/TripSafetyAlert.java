package org.coryd.trip.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** A trip that looked wrong, who was asked "Are you OK?", and what each said. */
@Entity
@Table(name = "trip_safety_alerts", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TripSafetyAlert extends BaseEntity {

    public enum Kind { STOPPED, OFF_ROUTE, SIGNAL_LOST }

    /** OPEN: waiting for answers. ALL_OK: everyone said fine. HELP: someone asked for help (an SOS was raised). ESCALATED: ops alerted. */
    public enum Status { OPEN, ALL_OK, HELP, ESCALATED }

    /** Explicit, never null - Hibernate silently drops null values from an element-collection map. */
    public enum Answer { WAITING, FINE, HELP }

    @Column(nullable = false, length = 36)
    private String routePostId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Kind kind;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Status status = Status.OPEN;

    private Double latitude;
    private Double longitude;

    private Instant escalatedAt;

    /** Everyone asked, and their answer - WAITING until they give one. */
    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "trip_safety_answers", schema = "trip", joinColumns = @JoinColumn(name = "alert_id"))
    @MapKeyColumn(name = "user_id", length = 36)
    @Column(name = "answer", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private Map<String, Answer> answers = new HashMap<>();
}
