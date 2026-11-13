package org.coryd.common.audit;

/** Which part of the console an audit entry comes from - and which service keeps it. */
public enum OpsAuditCategory {
    /** identity-service account_actions: suspend, reinstate, sign out everywhere, edits, tries given back. */
    ACCOUNT,
    /** identity-service account_actions: the admin team - invites, role changes, deactivations. */
    TEAM,
    /** profile-service verification_decision_log: identity checks and driver documents. */
    VERIFICATION,
    /** trip-service ops_audit_log: trip and booking cancellations, stuck-trip resolutions. */
    TRIPS,
    /** trip-service ops_audit_log: SOS, reports & disputes, ratings. */
    SAFETY,
    /** payment-service ops_money_audit: refunds, approvals, payouts, debt, bank-detail reveals. */
    MONEY,
    /** profile-service pii_reveal_log: an ops admin revealed a full NIN, licence number or date of birth (PII_REVEALED). */
    PRIVACY
}
