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
    IDENTITY_ATTEMPTS_RESET
}
