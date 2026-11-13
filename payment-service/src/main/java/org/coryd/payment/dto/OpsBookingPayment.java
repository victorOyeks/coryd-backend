package org.coryd.payment.dto;

import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PayoutStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What a booking was paid and refunded, for the Ops Console's rider history and receipts. amountPaid is every successful
 * charge; refunded has reached Paystack, refundPending hasn't yet - both only refunds of that payment, so neither ever
 * reads more than amountPaid; netPaid is what the rider is out of pocket for it. duplicateChargeRefunded is a second charge
 * for the same booking that went straight back - a separate charge (its amount is whatever Paystack took, fees included),
 * shown apart rather than counted against the booking's own payment.
 */
public record OpsBookingPayment(
        String bookingId,
        PayoutStatus status,
        PaymentMethod method,
        String cardType,
        String cardLast4,
        String reference,
        Instant paidAt,
        BigDecimal amountPaid,
        BigDecimal refunded,
        BigDecimal refundPending,
        BigDecimal netPaid,
        BigDecimal duplicateChargeRefunded
) {
}
