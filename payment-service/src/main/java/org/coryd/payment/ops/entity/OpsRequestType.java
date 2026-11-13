package org.coryd.payment.ops.entity;

/** The ops money actions that go through the two-person rule above the approval threshold. */
public enum OpsRequestType {
    /** A refund to the rider's card on a paid booking, full or partial. */
    MANUAL_REFUND,
    /** Asking Paystack again for a cancellation refund it refused (the hold's refund_due). */
    REFUND_RETRY,
    /** A failed Paystack settlement to a driver's bank. */
    PAYOUT_RETRY,
    /** A correction to what a driver owes - DEBIT adds to it, CREDIT takes it down. */
    DEBT_ADJUSTMENT
}
