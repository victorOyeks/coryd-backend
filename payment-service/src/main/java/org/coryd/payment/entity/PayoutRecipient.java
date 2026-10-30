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


/** A driver can save several bank accounts (Payout Setup screen) - isDefault marks the one
 * actual withdrawals go to; exactly one row per driver has it true (enforced in
 * PayoutRecipientService, not the database). */
@Entity
@Table(name = "payout_recipients", schema = "payment")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutRecipient extends BaseEntity {

    @Column(nullable = false)
    private String driverId;

    @Column(nullable = false, length = 100)
    private String bankName;

    @Column(nullable = false, length = 10)
    private String bankCode;

    @Column(nullable = false, length = 20)
    private String accountNumber;

    @Column(nullable = false, length = 100)
    private String accountName;

    /** Only on accounts saved while withdrawals existed - nothing reads it now. */
    private String gatewayRecipientCode;

    @Column(length = 100)
    private String gatewaySubaccountCode;

    /** Paystack's numeric id for the subaccount - its settlements are listed by id, not code. */
    @Column(length = 50)
    private String gatewaySubaccountId;

    /** Our share as last set on the subaccount on Paystack (5.00 = 5%) - null if never confirmed. */
    @Column(precision = 5, scale = 2)
    private java.math.BigDecimal gatewaySplitPercentage;

    @Builder.Default
    @Column(nullable = false)
    private boolean isDefault = false;
}
