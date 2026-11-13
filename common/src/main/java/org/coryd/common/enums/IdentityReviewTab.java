package org.coryd.common.enums;

/**
 * The tabs on the ops identity (NIN) screen. Each person is in exactly one, the first that matches:
 * APPROVED, IN_REVIEW, LOCKED_OUT, FAILED, PENDING.
 */
public enum IdentityReviewTab {
    /** Started a check that hasn't finished: Smile ID is still checking, or they closed the app mid-check. */
    PENDING,
    /** Waiting for an ops decision. */
    IN_REVIEW,
    /** Smile ID failed the last check (the person was asked to try again), or ops rejected it. */
    FAILED,
    /** Verified, by Smile ID or by ops. */
    APPROVED,
    /** Not verified and every try used: the app tells them to contact support. */
    LOCKED_OUT
}
