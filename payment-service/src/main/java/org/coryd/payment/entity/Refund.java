package org.coryd.payment.entity;

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
import org.coryd.payment.dto.RefundKind;
import org.coryd.payment.dto.RefundStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** A refund back to a card. PENDING while Paystack hasn't accepted it yet - RefundRetryJob keeps trying. */
@Entity
@Table(name = "refunds", schema = "payment")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Refund extends BaseEntity {

    @Column(nullable = false, length = 36)
    private String userId;

    @Column(length = 36)
    private String bookingId;

    @Column(length = 36)
    private String paymentTransactionId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RefundKind kind;

    private String reason;

    private Instant refundedAt;
}
