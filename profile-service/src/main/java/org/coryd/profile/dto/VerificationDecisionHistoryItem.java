package org.coryd.profile.dto;

import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.VerificationStatus;

import java.time.Instant;

/** One decision in the audit log - the rejection reason the driver saw, or the note left with an approval. */
public record VerificationDecisionHistoryItem(ReviewableItem item, String label, VerificationStatus status, String note,
                                              String decidedBy, Instant decidedAt) {
}
