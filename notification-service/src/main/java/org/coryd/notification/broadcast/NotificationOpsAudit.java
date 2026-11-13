package org.coryd.notification.broadcast;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

/** An ops action carried out by notification-service - who, what, on which, before and after (JSON), why. */
@Entity
@Table(name = "ops_audit_log", schema = "notification")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationOpsAudit extends BaseEntity {

    /** BROADCAST_CREATED, BROADCAST_SCHEDULED, BROADCAST_CANCELLED, REPORT_SENT. */
    @Column(nullable = false, length = 40)
    private String action;

    /** BROADCAST, REPORT. */
    @Column(nullable = false, length = 20)
    private String targetType;

    @Column(nullable = false, length = 36)
    private String targetId;

    @Column(nullable = false, length = 36)
    private String actorId;

    @Column(length = 120)
    private String actorName;

    @Column(columnDefinition = "TEXT")
    private String beforeState;

    @Column(columnDefinition = "TEXT")
    private String afterState;

    @Column(nullable = false, length = 500)
    private String reason;
}
