package org.coryd.payment.dto;

import org.coryd.payment.entity.FareHold;
import org.coryd.payment.entity.PaymentTransaction;

import java.math.BigDecimal;

/**
 * What a cancellation sends back to the card: the fare left on the hold less the fee kept, plus the card fee when it
 * goes back too. refund = fareBack + cardFeeBack.
 */
public record CancellationRefund(String bookingId, BigDecimal refund, BigDecimal fareBack, BigDecimal cardFeeBack, BigDecimal kept) {

    public static CancellationRefund none(String bookingId) {
        return new CancellationRefund(bookingId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** The one split - EarningsService.refund sends exactly this. The hold's amount is already net of earlier ops refunds. */
    public static CancellationRefund of(String bookingId, FareHold hold, PaymentTransaction transaction, BigDecimal fee, boolean refundCardFee) {
        BigDecimal kept = fee == null ? BigDecimal.ZERO : fee.max(BigDecimal.ZERO).min(hold.getAmount());
        BigDecimal fareBack = hold.getAmount().subtract(kept);
        BigDecimal cardFeeBack = refundCardFee ? transaction.cardFeeOrZero() : BigDecimal.ZERO;
        return new CancellationRefund(bookingId, fareBack.add(cardFeeBack), fareBack, cardFeeBack, kept);
    }
}
