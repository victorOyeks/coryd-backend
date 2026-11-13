package org.coryd.identity.dto.ops;

/** Where an ops admin stands - derived, never stored. */
public enum OpsAdminStatus {
    /** Has a password and console access. */
    ACTIVE,
    /** Invited; the link is still valid and unused. */
    INVITED,
    /** Invited; the link ran out (72h) before they used it - resend it. */
    INVITE_EXPIRED,
    /** Invited; the invite was withdrawn - resend to invite them again. */
    INVITE_REVOKED,
    /** Console access removed by a super admin. */
    DEACTIVATED,
    /** The whole account is suspended (account action, not a team action). */
    SUSPENDED
}
