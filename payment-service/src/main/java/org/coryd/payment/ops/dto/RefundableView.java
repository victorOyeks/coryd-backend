package org.coryd.payment.ops.dto;

import java.math.BigDecimal;

/**
 * What a booking's payment can still give back. refunded counts only refunds of this payment (cancellations and ops
 * refunds) and is never more than paid; a duplicate charge that went back is a separate charge and is shown apart.
 * fareSettled: false while the seat is neither completed nor cancelled - a full refund then is better done by cancelling
 * the booking.
 */
public record RefundableView(String bookingId, String paymentId, String reference, String paymentStatus, BigDecimal paid,
                             BigDecimal refunded, BigDecimal refundPending, BigDecimal awaitingApproval, BigDecimal refundable,
                             BigDecimal duplicateChargeRefunded, BigDecimal approvalThreshold, String riderId, String driverId,
                             boolean fareSettled) {
}
