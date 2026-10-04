package org.coryd.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.coryd.common.entity.BaseEntity;

import java.math.BigDecimal;

/**
 * One booking's fare and what became of it - released to the driver's earnings on completion or
 * refunded to the rider, exactly once. The money itself moved at charge time; this is the ledger.
 */
@Entity
@Table(name = "fare_holds", schema = "payment")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FareHold extends BaseEntity {

    @Column(nullable = false)
    private String paymentTransactionId;

    @Column(nullable = false)
    private String bookingId;

    @Column(nullable = false)
    private String driverId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Builder.Default
    @Column(nullable = false)
    private boolean released = false;

    @Builder.Default
    @Column(nullable = false)
    private boolean refunded = false;

    /** The part of this fare routed to us to pay down the driver's settlement debt. */
    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal debtRecovery = BigDecimal.ZERO;

    /** Owed back to the rider's card but not yet accepted by Paystack - retried until it is. */
    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal refundDue = BigDecimal.ZERO;

    private String refundReason;
}
