package org.coryd.payment.dto;

public enum RefundStatus {
    /** Paystack hasn't accepted it yet - it's retried until it does. */
    PENDING,
    /** Paystack accepted it; the bank shows it within a few working days. */
    REFUNDED
}
