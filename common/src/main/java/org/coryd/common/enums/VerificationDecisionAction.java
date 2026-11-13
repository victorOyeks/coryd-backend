package org.coryd.common.enums;

/** What an entry in the verification decision audit log did. Entries written before this was recorded have none. */
public enum VerificationDecisionAction {
    /** Approved by ops with no third-party result standing against it, or approved by Smile ID itself. */
    APPROVED,
    /** Approved by ops over a third-party result that hadn't approved it (Smile ID failed, flagged or asked for a retry). */
    OVERRIDE_APPROVED,
    /** Rejected by ops, with the reason the person sees. */
    REJECTED,
    /** Ops asked the driver to upload this document again, with the reason they see. */
    RESUBMISSION_REQUESTED,
    /** Ops gave the person their identity-check tries back. The status doesn't change. */
    ATTEMPTS_RESET,
    /**
     * Smile ID's result came in after ops had decided the licence while it was out. Recorded for the audit trail with what
     * Smile ID said (providerOutcome); ops' decision stands (status is unchanged). Older entries: the result overrode ops.
     */
    PROVIDER_RESULT,
    /** Ops changed the vehicle's identity (plate, make, model, year) on an approved vehicle - its registration is back in review. */
    SENT_BACK_TO_REVIEW
}
