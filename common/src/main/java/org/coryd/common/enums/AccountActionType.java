package org.coryd.common.enums;

/** What an entry in a user's account history (identity-service's account_actions) records. */
public enum AccountActionType {
    /** Ops suspended the account - every session ended, sign-in refused. */
    SUSPENDED,
    /** Ops lifted the suspension. Old sessions stay dead; the person signs in again. */
    REINSTATED,
    /** Ops ended every session on every device. */
    SIGNED_OUT_EVERYWHERE,
    /** Ops changed the person's name or email. */
    DETAILS_EDITED,
    /** Ops changed a driver's vehicle details. */
    VEHICLE_EDITED,
    /** Ops gave a driver their licence-check tries back. */
    LICENCE_ATTEMPTS_RESET,
    /** Ops gave the person their identity (NIN) check tries back. */
    IDENTITY_ATTEMPTS_RESET,
    // ---- the ops admin team (phase 6) - recorded on the admin's own account; the unified audit log shows them as TEAM ----
    /** A super admin invited this person to the ops console (role in changes). */
    OPS_ADMIN_INVITED,
    /** A fresh invite link was sent; the earlier one stopped working. */
    OPS_INVITE_RESENT,
    /** The pending invite was withdrawn - its link no longer works. */
    OPS_INVITE_REVOKED,
    /** The invited admin set their password - the account is now in use. */
    OPS_INVITE_ACCEPTED,
    /** A super admin changed this admin's ops role (before/after in changes). */
    OPS_ROLE_CHANGED,
    /** A super admin removed this admin's console access - every session ended at once. */
    OPS_ADMIN_DEACTIVATED,
    /** A super admin gave the console access back; the ended sessions stay ended. */
    OPS_ADMIN_REACTIVATED,
    // ---- ops admin two-factor sign-in (phase 7) - on the admin's own account; the audit log shows them as TEAM ----
    /** The admin turned two-factor sign-in on (or enrolled at sign-in because it's required). */
    MFA_ENABLED,
    /** The admin turned two-factor sign-in off (reason recorded). */
    MFA_DISABLED,
    /** A super admin cleared this admin's two-factor sign-in - every session ended; they set it up again at next sign-in. */
    MFA_RESET,
    /** The admin signed in with one of their one-time recovery codes instead of the app. */
    RECOVERY_CODE_USED,
    /** The admin replaced their recovery codes - the old ones stopped working. */
    MFA_RECOVERY_CODES_REGENERATED;

    /** Whether this is an admin-team action rather than one on a rider's or driver's account. */
    public boolean isTeamAction() {
        return name().startsWith("OPS_") || name().startsWith("MFA_") || this == RECOVERY_CODE_USED;
    }
}
