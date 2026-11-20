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
import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PaymentPurpose;
import org.coryd.common.enums.PayoutStatus;

import java.math.BigDecimal;

@Entity
@Table(name = "payment_transactions", schema = "payment")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentTransaction extends BaseEntity {

    /** The card fee actually charged - 0 for payments made before it was recorded. */
    public BigDecimal cardFeeOrZero() {
        return cardFee == null ? BigDecimal.ZERO : cardFee;
    }

    /** What the card was charged - amount alone for payments made before the fee was recorded. */
    public BigDecimal chargedTotal() {
        return amountCharged != null ? amountCharged : amount.add(cardFeeOrZero());
    }

    /** Every payment is for a booking; nullable only because the column predates that. */
    private String bookingId;

    @Column(nullable = false)
    private String riderId;

    /** What the payer owed: the fare, or the debt being repaid. The card fee is on top of it, below. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    /** The card fee on top of amount (CardFeeCalculator) - quoted at initiate, replaced by Paystack's real figure on success. */
    @Builder.Default
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal cardFee = BigDecimal.ZERO;

    /** What the card was charged: amount + cardFee. Null on payments made before the fee was recorded. */
    @Column(precision = 10, scale = 2)
    private BigDecimal amountCharged;

    @Column(nullable = false, unique = true)
    private String gatewayReference;

    @Column(length = 512)
    private String authorizationUrl;

    /** The card it was paid with, for the rider's payment history. */
    @Column(length = 50)
    private String cardType;

    @Column(length = 4)
    private String cardLast4;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private PayoutStatus status = PayoutStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private PaymentMethod paymentMethod = PaymentMethod.PAYSTACK;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private PaymentPurpose purpose = PaymentPurpose.BOOKING_PAYMENT;
}
