package org.coryd.common.enums;

/** The ops identity (NIN) list's sortable columns. */
public enum IdentityReviewSort {
    /** When the latest check was started - the default, oldest first. */
    SUBMITTED_AT,
    NAME,
    /** Tries used. */
    ATTEMPTS,
    /** When the last decision was made. */
    DECIDED_AT,
    /** The tab. */
    STATUS
}
