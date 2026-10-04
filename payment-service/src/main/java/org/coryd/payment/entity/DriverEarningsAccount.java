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

@Entity
@Table(name = "driver_earnings_accounts", schema = "payment")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DriverEarningsAccount extends BaseEntity {

    @Column(nullable = false, unique = true)
    private String driverId;

    @Builder.Default
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal lifetimeGross = BigDecimal.ZERO;

    @Builder.Default
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal lifetimeCommission = BigDecimal.ZERO;

    /** Owed back to us after a refund Paystack could no longer claw out of an unsettled split. */
    @Builder.Default
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal settlementDebt = BigDecimal.ZERO;
}
