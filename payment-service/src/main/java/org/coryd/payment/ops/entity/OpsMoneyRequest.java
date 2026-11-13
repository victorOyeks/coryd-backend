package org.coryd.payment.ops.entity;

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

import java.math.BigDecimal;
import java.time.Instant;

/** One ops money action from the moment it was asked for - see coryd:110. */
@Entity
@Table(name = "ops_money_requests", schema = "payment")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class OpsMoneyRequest extends BaseEntity {

    public enum Direction { DEBIT, CREDIT }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OpsRequestType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OpsRequestStatus status;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Direction direction;

    @Column(length = 36)
    private String bookingId;

    @Column(length = 36)
    private String riderId;

    @Column(length = 36)
    private String driverId;

    /** The fare hold (REFUND_RETRY) or settlement (PAYOUT_RETRY) this acts on. */
    @Column(length = 50)
    private String targetId;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(length = 100)
    private String idempotencyKey;

    @Builder.Default
    @Column(nullable = false)
    private boolean needsApproval = false;

    @Column(nullable = false, length = 36)
    private String requestedBy;

    @Column(length = 120)
    private String requestedByName;

    @Column(length = 36)
    private String decidedBy;

    @Column(length = 120)
    private String decidedByName;

    @Column(length = 500)
    private String decisionReason;

    private Instant decidedAt;

    private Instant executedAt;

    @Column(length = 500)
    private String failureReason;

    /** The refunds row a completed MANUAL_REFUND wrote. */
    @Column(length = 36)
    private String refundId;

    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;
}
