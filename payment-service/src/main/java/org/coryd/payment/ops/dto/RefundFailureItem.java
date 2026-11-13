package org.coryd.payment.ops.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A refund that hasn't reached the rider. kind CANCELLATION: Paystack refused a cancellation refund - id is the fare hold,
 * retried automatically, and by hand with POST /refund-failures/{id}/retry. STUCK: a refund PENDING with nothing retrying
 * it - id is the refund; settle it on Paystack. MANUAL: an ops refund Paystack refused - id is the request, retried with
 * POST /requests/{id}/retry. openRequestId: a hand retry already waiting for approval.
 */
public record RefundFailureItem(Kind kind, String id, String bookingId, String riderId, String driverId, BigDecimal amount,
                                String reason, Instant failedSince, String detail, boolean autoRetry, boolean retryable,
                                String openRequestId, String reference) {

    public enum Kind { CANCELLATION, STUCK, MANUAL }
}
