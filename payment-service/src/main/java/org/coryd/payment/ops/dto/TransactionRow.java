package org.coryd.payment.ops.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the Transactions list. status: PAYMENT PENDING/PAID/FAILED · REFUND PENDING/REFUNDED · PAYOUT
 * PENDING/PROCESSING/PAID/FAILED. kind: PAYMENT BOOKING_PAYMENT/DEBT_REPAYMENT · REFUND CANCELLATION/MANUAL/
 * DUPLICATE_CHARGE/OVERPAYMENT. reference is the provider's: the Paystack charge reference (for a refund, the charge it
 * went back to) or the Paystack settlement id.
 */
public record TransactionRow(TransactionType type, String id, Instant occurredAt, String status, BigDecimal amount, String reference,
                             String bookingId, String riderId, String driverId, String kind, String reason, String cardType,
                             String cardLast4) {
}
