package org.coryd.trip.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

/** One Ops Console action on a trip, a booking, an SOS, an issue report or a rating: who, what, on which, the state before and after (JSON), and why. */
@Entity
@Table(name = "ops_audit_log", schema = "trip")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class OpsAuditLog extends BaseEntity {

    public enum Action {
        CANCEL_TRIP, CANCEL_BOOKING, RESOLVE_STUCK_COMPLETED, RESOLVE_STUCK_CANCELLED,
        SOS_ACKNOWLEDGED, SOS_NOTE, SOS_ESCALATED, SOS_RESOLVED,
        REPORT_ASSIGNED, REPORT_NOTE, REPORT_RESOLVED, REPORT_DISMISSED,
        RATING_HIDDEN, RATING_RESTORED
    }

    /** SOS covers SOS-queue safety checks too; ISSUE is an issue report. */
    public enum TargetType { ROUTE, BOOKING, SOS, ISSUE, RATING }

    @Column(nullable = false, length = 36)
    private String actorId;

    @Column(length = 120)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Action action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TargetType targetType;

    @Column(nullable = false, length = 36)
    private String targetId;

    /** The trip it happened on - null for an account-level issue report or a seeded rating. */
    @Column(length = 36)
    private String routePostId;

    @Column(columnDefinition = "TEXT")
    private String beforeState;

    @Column(columnDefinition = "TEXT")
    private String afterState;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private CancellationFault fault;
}
