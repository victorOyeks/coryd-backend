package org.coryd.payment.entity;

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
import java.time.Instant;

/** One Paystack settlement to a driver's bank, as last reported by Paystack. status is Paystack's
 * own value: success, processing, pending or failed. */
@Entity
@Table(name = "driver_settlements", schema = "payment")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DriverSettlement extends BaseEntity {

    @Column(nullable = false, length = 36)
    private String driverId;

    @Column(nullable = false, unique = true, length = 50)
    private String gatewaySettlementId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status;

    private Instant settlementDate;
}
