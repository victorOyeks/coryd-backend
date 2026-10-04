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

@Entity
@Table(name = "earnings_transactions", schema = "payment")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class EarningsTransaction extends BaseEntity {

    @Column(nullable = false)
    private String driverId;

    @Column(nullable = false)
    private String bookingId;

    /** So a "Trip" row on the driver's earnings screen can link back to the Trip Receipt screen without a second lookup. */
    private String routePostId;

    /** Who paid for this trip - so the earnings screen can show the rider on a "Trip" row without a per-row cross-service lookup. Nullable only for rows written before this column existed. */
    private String riderId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal grossFare;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal commissionAmount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal netCredit;
}
