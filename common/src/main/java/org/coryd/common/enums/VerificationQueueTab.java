package org.coryd.common.enums;

/** Ops Console verification queue's tabs - omit the query param entirely for "All". A driver sits in the
 * first that applies: RESUBMITTED (re-uploaded something ops rejected), IN_REVIEW (some items decided,
 * others still waiting), PENDING (submitted, nothing decided yet). */
public enum VerificationQueueTab {
    PENDING,
    IN_REVIEW,
    RESUBMITTED
}
