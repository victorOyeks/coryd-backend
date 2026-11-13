package org.coryd.profile.dto;

import org.coryd.common.enums.DecisionSubject;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.VerificationDecisionAction;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.VerificationDecisionLog;

import java.time.Instant;

/**
 * One decision in the audit log - the rejection reason the person saw, or the note left with an approval. subject is what
 * was decided: a driver's item, or IDENTITY for the NIN check (item is then null - it only names a driver's four items).
 * status is the status the decision left it in, previousStatus the one before (null on older entries, as is action).
 * overrodeProvider: an approval over what Smile ID had said, which providerOutcome names. decidedBy is the admin's name
 * (or SMILE_ID), decidedById their user id.
 */
public record VerificationDecisionHistoryItem(ReviewableItem item, String label, VerificationStatus status, String note,
                                              String decidedBy, Instant decidedAt, DecisionSubject subject,
                                              VerificationDecisionAction action, VerificationStatus previousStatus,
                                              boolean overrodeProvider, String providerOutcome, String decidedById) {

    public static VerificationDecisionHistoryItem from(VerificationDecisionLog l) {
        return new VerificationDecisionHistoryItem(l.getItem().reviewableItem(), l.getItem().label(), l.getStatus(), l.getNote(),
                l.getDecidedBy(), l.getCreatedAt(), l.getItem(), l.getAction(), l.getPreviousStatus(), l.isOverrodeProvider(),
                l.getProviderOutcome(), l.getDecidedById());
    }
}
