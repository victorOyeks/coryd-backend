package org.coryd.ops.dto;

/**
 * Where a cancelled seat's refund is, from payment-service. NOT_PAID: nothing was charged. NO_REFUND_DUE: the fee kept
 * the whole fare. NOT_STARTED: payment-service hasn't refunded yet (the event may still be on its way). PENDING: handed
 * to Paystack, not yet accepted - payment-service retries. PARTIAL: less than expected so far. REFUNDED: done (the
 * money still takes hours to days to reach the card). UNKNOWN: payment-service couldn't be reached. NONE: not cancelled.
 */
public enum RefundState {
    NONE,
    NOT_PAID,
    NO_REFUND_DUE,
    NOT_STARTED,
    PENDING,
    PARTIAL,
    REFUNDED,
    UNKNOWN
}
