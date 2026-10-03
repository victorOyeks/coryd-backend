package org.coryd.common.enums;

/**
 * What a provider check concluded. Only MATCH is allowed to move an item forward on its own -
 * every other outcome leaves the item exactly where it was, pending a human, because a provider
 * being unreachable or a name being spelt differently is not evidence of fraud.
 */
public enum VerificationCheckOutcome {

    MATCH,

    /** The record exists but the name on it is too far from what the driver entered. */
    NAME_MISMATCH,

    /** Plate found, but the declared make or colour contradicts the register. */
    DETAILS_MISMATCH,

    /** The provider has no such licence or plate. */
    NOT_FOUND,

    /** Timeout, rate limit, bad key, malformed response - anything that is our problem, not the driver's. */
    PROVIDER_ERROR
}
