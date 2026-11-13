package org.coryd.trip.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;
import org.coryd.common.enums.SosEscalationTarget;
import org.coryd.common.enums.SosKind;
import org.coryd.common.enums.SosOutcome;
import org.coryd.common.enums.SosStatus;

import java.time.Instant;

/**
 * An SOS, or an unanswered "Are you OK?" check opened in the same queue (kind SAFETY_CHECK, triggeredBy "SYSTEM"). The ops
 * handling fields are the Ops Console's alone - JSON-ignored so the apps' SOS response stays what it was.
 */
@Entity
@Table(name = "sos_incidents", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SosIncident extends BaseEntity {

    /** Who raises a safety-check incident. */
    public static final String SYSTEM = "SYSTEM";

    /** Null for a driver's SOS raised on the route itself. */
    private String bookingId;

    private String routePostId;

    @Column(nullable = false)
    private String triggeredBy;

    /** RIDER or DRIVER - SYSTEM for a safety check. */
    private String triggeredByRole;

    private Double latitude;
    private Double longitude;

    private Instant opsNotifiedAt;

    @JsonIgnore
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SosKind kind = SosKind.SOS;

    @JsonIgnore
    @Column(length = 36)
    private String safetyAlertId;

    @JsonIgnore
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SosStatus status = SosStatus.OPEN;

    /** JSON array of {name, phone} - every emergency contact the SOS texted. */
    @JsonIgnore
    @Column(columnDefinition = "TEXT")
    private String contactsNotified;

    @JsonIgnore
    private Instant acknowledgedAt;
    @JsonIgnore
    @Column(length = 36)
    private String acknowledgedBy;
    @JsonIgnore
    @Column(length = 120)
    private String acknowledgedByName;

    @JsonIgnore
    private Instant escalatedAt;
    @JsonIgnore
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SosEscalationTarget escalatedTo;
    @JsonIgnore
    @Column(length = 200)
    private String escalationContact;
    @JsonIgnore
    private Instant escalationContactedAt;
    @JsonIgnore
    @Column(length = 100)
    private String escalationReference;

    @JsonIgnore
    private Instant resolvedAt;
    @JsonIgnore
    @Column(length = 36)
    private String resolvedBy;
    @JsonIgnore
    @Column(length = 120)
    private String resolvedByName;
    @JsonIgnore
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private SosOutcome outcome;
    @JsonIgnore
    @Column(length = 1000)
    private String resolutionNote;

    /** Thresholds already re-alerted - bumped only by SosIncidentRepository.claimRealert. */
    @JsonIgnore
    @Builder.Default
    @Column(nullable = false)
    private int realertLevel = 0;
}
