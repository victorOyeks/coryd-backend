package org.coryd.payment.entity;

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
import org.coryd.common.enums.PayoutStatus;

import java.math.BigDecimal;

@Entity
@Table(name = "payouts", schema = "payment")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Payout extends BaseEntity {

    @Column(nullable = false)
    private String driverId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /** The reference sent on the Paystack /transfer call - what the transfer.success/failed webhook correlates back to, same field name/role as PaymentTransaction.gatewayReference. */
    @Column(unique = true)
    private String gatewayReference;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private PayoutStatus status = PayoutStatus.PENDING;

    private String failureReason;
}
