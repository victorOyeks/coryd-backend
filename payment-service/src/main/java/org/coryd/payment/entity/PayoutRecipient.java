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

    /** Non-null means "Verified" on the Payout Setup screen - the real Paystack transfer recipient exists, not just locally-saved bank details. */
    private String gatewayRecipientCode;

    @Column(length = 100)
    private String gatewaySubaccountCode;

    /** Paystack's numeric id for the subaccount - its settlements are listed by id, not code. */
    @Column(length = 50)
    private String gatewaySubaccountId;

    @Builder.Default
    @Column(nullable = false)
    private boolean isDefault = false;
}
