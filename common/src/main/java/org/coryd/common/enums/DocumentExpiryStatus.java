package org.coryd.common.enums;

/**
 * Where a tracked document stands against its expiry date - each has exactly one. EXPIRED once the date has passed;
 * EXPIRING_SOON from 14 days before it; VERIFIED when approved with more than 14 days left; NOT_VERIFIED when it has
 * more than 14 days left but isn't approved (waiting for review, or rejected) - that's the Verification Queue's work.
 */
public enum DocumentExpiryStatus {
    EXPIRED,
    EXPIRING_SOON,
    VERIFIED,
    NOT_VERIFIED
}
