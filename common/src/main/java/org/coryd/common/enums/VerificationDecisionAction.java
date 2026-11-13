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
    /** Smile ID's late result overrode an ops decision made while it was out. */
    PROVIDER_RESULT
}
