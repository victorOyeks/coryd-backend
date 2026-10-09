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
import org.coryd.payment.dto.DebtEntryType;

import java.math.BigDecimal;

/** One change to what a driver owes. amount is signed: positive adds to it, negative pays it down. */
@Entity
@Table(name = "driver_debt_entries", schema = "payment")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DriverDebtEntry extends BaseEntity {

    @Column(nullable = false, length = 36)
    private String driverId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DebtEntryType type;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceAfter;

    @Column(length = 36)
    private String bookingId;

    @Column(length = 100)
    private String reference;

    private String note;
}
