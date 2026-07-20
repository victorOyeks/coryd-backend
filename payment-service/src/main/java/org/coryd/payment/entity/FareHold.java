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
 * The rider's paid fare, held until trip completion (Section 10.4) before it
 * becomes creditable to the driver's earnings ledger.
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
}
