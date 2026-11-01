package org.coryd.common.enums;

/**
 * Where a driver stands in the Drivers Directory - each driver has exactly one, the first that applies:
 * SUSPENDED (ops suspended the account), VERIFIED (all four items approved), RESUBMITTED (re-uploaded something ops
 * rejected, waiting again), PENDING (anything else - not finished, waiting for review, or rejected).
 */
public enum DriverDirectoryStatus {
    SUSPENDED,
    VERIFIED,
    RESUBMITTED,
    PENDING
}
