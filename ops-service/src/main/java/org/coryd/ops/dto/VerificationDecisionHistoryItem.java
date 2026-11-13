package org.coryd.ops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.coryd.common.enums.DecisionSubject;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.VerificationDecisionAction;
import org.coryd.common.enums.VerificationStatus;

import java.time.Instant;

/**
 * One decision in the audit log - the rejection reason the person saw, or the note left with an approval. subject is a
 * driver's item or IDENTITY (the NIN check, when item is null). status is what
 * the decision left the item in, previousStatus what it was before; action what was done (null on entries from before
 * actions were recorded). overrodeProvider: an approval over Smile ID's result, which providerOutcome names. decidedBy is
 * the admin's name (or SMILE_ID), decidedById their user id.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationDecisionHistoryItem(ReviewableItem item, String label, VerificationStatus status, String note,
                                              String decidedBy, Instant decidedAt, DecisionSubject subject,
                                              VerificationDecisionAction action, VerificationStatus previousStatus,
                                              boolean overrodeProvider, String providerOutcome, String decidedById) {
}
