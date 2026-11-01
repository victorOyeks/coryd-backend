package org.coryd.profile.dto;

import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.VerificationQueueTab;
import org.coryd.common.enums.VerificationStatus;

import java.time.Instant;
import java.util.List;

/** One row of the ops verification queue. waitingSince is when the oldest still-waiting item arrived -
 * "in queue for" counts from it. items lists all four, so the row can show which were submitted. */
public record VerificationQueueItem(
        String driverId,
        String firstName,
        String lastName,
        String photoUrl,
        VerificationQueueTab tab,
        Instant waitingSince,
        List<Entry> items
) {
    /** The earliest submission among the items - the "Submitted" column. */
    @com.fasterxml.jackson.annotation.JsonProperty("submittedAt")
    public Instant submittedAt() {
        return items == null ? null : items.stream().map(Entry::submittedAt).filter(java.util.Objects::nonNull)
                .min(java.util.Comparator.naturalOrder()).orElse(null);
    }

    /**
     * awaitingProvider: the licence is with Smile ID and its result hasn't come yet (status reads PENDING). Ops can
     * still decide it - Smile ID's result overrides that when it arrives. submittedAt is then when the check started.
     */
    public record Entry(ReviewableItem item, VerificationStatus status, Instant submittedAt, boolean resubmitted, boolean awaitingProvider) {

        public Entry(ReviewableItem item, VerificationStatus status, Instant submittedAt, boolean resubmitted) {
            this(item, status, submittedAt, resubmitted, false);
        }

        /** Ops's turn: sent for review, or still out with Smile ID. */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean awaitingOps() {
            return status == VerificationStatus.IN_REVIEW || awaitingProvider;
        }

        /** "Driver's licence", "Vehicle registration"... - the document chip. */
        @com.fasterxml.jackson.annotation.JsonProperty("label")
        public String label() {
            return item == null ? null : item.label();
        }
    }
}
