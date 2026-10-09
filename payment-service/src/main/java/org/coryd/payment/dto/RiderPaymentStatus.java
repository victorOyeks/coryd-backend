package org.coryd.payment.dto;

public enum RiderPaymentStatus {
    PAID,
    FAILED,
    REFUNDED,
    /** Owed to the card but not yet accepted by Paystack - retried until it is. */
    REFUND_PROCESSING
}
