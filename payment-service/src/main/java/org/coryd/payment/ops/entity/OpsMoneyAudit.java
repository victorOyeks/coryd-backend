package org.coryd.payment.ops.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

import java.math.BigDecimal;

/** One ops money action: who, what, on which, how much, before/after (JSON) and why - see coryd:111. */
@Entity
@Table(name = "ops_money_audit", schema = "payment")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class OpsMoneyAudit extends BaseEntity {

    public enum Action {
        REFUND_REQUESTED, REFUND_EXECUTED, REFUND_FAILED,
        REFUND_RETRY_REQUESTED, REFUND_RETRIED, REFUND_RETRY_FAILED,
        PAYOUT_RETRY_REQUESTED, PAYOUT_RETRIED, PAYOUT_RETRY_FAILED,
        DEBT_ADJUSTMENT_REQUESTED, DEBT_ADJUSTED, DEBT_ADJUSTMENT_FAILED,
        REQUEST_APPROVED, REQUEST_REJECTED, REQUEST_CANCELLED, REQUEST_RESUBMITTED,
        PAYOUT_ACCOUNT_REVEALED
    }

    public enum TargetType { BOOKING, FARE_HOLD, SETTLEMENT, DRIVER, REQUEST, PAYOUT_ACCOUNT }

    @Column(nullable = false, length = 36)
    private String actorId;

    @Column(length = 120)
    private String actorName;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Action action;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TargetType targetType;

    @Column(nullable = false, length = 50)
    private String targetId;

    @Column(length = 36)
    private String requestId;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(columnDefinition = "TEXT")
    private String beforeState;

    @Column(columnDefinition = "TEXT")
    private String afterState;

    @Column(length = 500)
    private String reason;
}
