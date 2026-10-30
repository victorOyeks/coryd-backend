package org.coryd.profile.service;

import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.profile.entity.SmileIdRetryReason;

/** What a Smile ID result comes to: approve, ask the person to retry, or hand to ops. */
record SmileIdDecision(Kind kind, VerificationCheckOutcome outcome, Double nameMatchScore, String summary,
                       SmileIdRetryReason retryReason, boolean usesATry) {

    enum Kind { APPROVE, RETRY, OPS }

    /** Approval always comes from a result about the person, so it uses a try. */
    static SmileIdDecision approve(double score, String summary) {
        return new SmileIdDecision(Kind.APPROVE, VerificationCheckOutcome.MATCH, score, summary, null, true);
    }

    /** Approved on a partial name match - recorded as PARTIAL_NAME_MATCH so it lands in ops' review list. */
    static SmileIdDecision approveForReview(double score, String summary) {
        return new SmileIdDecision(Kind.APPROVE, VerificationCheckOutcome.PARTIAL_NAME_MATCH, score, summary, null, true);
    }

    static SmileIdDecision retry(SmileIdRetryReason reason, VerificationCheckOutcome outcome, boolean usesATry, String summary) {
        return new SmileIdDecision(Kind.RETRY, outcome, null, summary, reason, usesATry);
    }

    static SmileIdDecision ops(VerificationCheckOutcome outcome, Double score, boolean usesATry, String summary) {
        return new SmileIdDecision(Kind.OPS, outcome, score, summary, null, usesATry);
    }
}
