package org.coryd.payment.dto;

import org.coryd.common.enums.PaymentMethod;
import org.coryd.common.enums.PayoutStatus;

import java.math.BigDecimal;

/**
 * A booking's payment for the receipt and Pay Now. amount: the fare. cardFee: the card fee the rider covered on top (0 on
 * payments from before it was recorded). amountCharged: what the card was charged - amount + cardFee.
 */
public record PaymentSummaryResponse(PaymentMethod paymentMethod, PayoutStatus status, BigDecimal amount, BigDecimal cardFee,
                                     BigDecimal amountCharged, String cardType, String cardLast4) {
}
