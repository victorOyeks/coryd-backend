package org.coryd.notification.broadcast;

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

import java.time.Instant;

/** A message ops sends to an audience (docs/ops-console/broadcasts.md). */
@Entity
@Table(name = "broadcasts", schema = "notification")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Broadcast extends BaseEntity {

    public enum Status { SCHEDULED, SENDING, SENT, CANCELLED, FAILED }

    @Column(nullable = false, length = 80)
    private String title;

    @Column(nullable = false, length = 1000)
    private String body;

    /** Comma-separated BroadcastChannel names. */
    @Column(nullable = false, length = 60)
    private String channels;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AudienceType audienceType;

    private Integer audienceActiveDays;

    /** JSON array, for a USERS audience. */
    @Column(columnDefinition = "TEXT")
    private String audienceIds;

    @Column(columnDefinition = "TEXT")
    private String audienceRefs;

    @Builder.Default
    @Column(nullable = false)
    private boolean urgent = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    /** What ops asked for. */
    @Column(nullable = false)
    private Instant requestedFor;

    /** When it actually goes - later than requestedFor when quiet hours moved it. */
    @Column(nullable = false)
    private Instant scheduledFor;

    @Builder.Default
    @Column(nullable = false)
    private boolean deferredForQuietHours = false;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(nullable = false, length = 36)
    private String createdBy;

    @Column(length = 120)
    private String createdByName;

    @Column(length = 36)
    private String cancelledBy;

    @Column(length = 120)
    private String cancelledByName;

    @Column(length = 500)
    private String cancelReason;

    private Instant cancelledAt;

    private Instant audienceResolvedAt;

    private Instant startedAt;

    private Instant finishedAt;

    @Builder.Default
    @Column(nullable = false)
    private int recipientsTotal = 0;

    @Builder.Default
    @Column(nullable = false)
    private int recipientsSent = 0;

    @Builder.Default
    @Column(nullable = false)
    private int recipientsFailed = 0;

    @Builder.Default
    @Column(nullable = false)
    private int recipientsSkipped = 0;

    @Column(length = 500)
    private String lastError;
}
