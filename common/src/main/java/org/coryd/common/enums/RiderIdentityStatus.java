package org.coryd.common.enums;

/**
 * A rider's identity (NIN) check as the Riders Directory shows it: NOT_STARTED when they've never submitted one,
 * otherwise their tab on the ops identity screen (see IdentityReviewTab), worked out the same way.
 */
public enum RiderIdentityStatus {
    NOT_STARTED,
    PENDING,
    IN_REVIEW,
    FAILED,
    APPROVED,
    LOCKED_OUT;

    /** NOT_STARTED for null (no check); otherwise the identity screen's tab of the same name. */
    public static RiderIdentityStatus of(IdentityReviewTab tab) {
        return tab == null ? NOT_STARTED : valueOf(tab.name());
    }
}
