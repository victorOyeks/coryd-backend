package org.coryd.payment.ops.entity;

public enum OpsRequestStatus {
    /** Above the threshold - waits for a different admin to approve or reject it. Nothing has been sent. */
    PENDING_APPROVAL,
    /** Running now (approved, or at/below the threshold). */
    EXECUTING,
    /** Done - for a refund, Paystack accepted it. */
    COMPLETED,
    /** Tried and refused (Paystack declined, account invalid...) - failureReason says why; can be retried. */
    FAILED,
    /** A second admin said no - nothing was sent. */
    REJECTED,
    /** The requester withdrew it before anyone decided - nothing was sent. */
    CANCELLED;

    public boolean open() {
        return this == PENDING_APPROVAL || this == EXECUTING;
    }
}
