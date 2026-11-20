package org.coryd.common.enums;

/**
 * Where a driver stands in the Drivers Directory - each driver has exactly one, the first that applies:
 * SUSPENDED (ops suspended the account), VERIFIED (all four items approved and, unless IDENTITY_VERIFICATION_ENFORCEMENT is OFF, the identity (NIN) check too), RESUBMITTED (re-uploaded something ops
 * rejected, waiting again), PENDING (anything else - not finished, waiting for review, rejected, or every item approved with the NIN check outstanding: ninPending).
 */
public enum DriverDirectoryStatus {
    SUSPENDED,
    VERIFIED,
    RESUBMITTED,
    PENDING
}
