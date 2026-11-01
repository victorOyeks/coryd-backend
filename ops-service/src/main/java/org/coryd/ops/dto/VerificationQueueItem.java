package org.coryd.ops.dto;

import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.VerificationQueueTab;
import org.coryd.common.enums.VerificationStatus;

import java.time.Instant;
import java.util.List;

/** One row of the verification queue. "In queue for" counts from waitingSince; items lists all four. */
public record VerificationQueueItem(
        DriverSummary driver,
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

    /** awaitingProvider: the licence is still with Smile ID (status PENDING) - ops can decide it now; Smile ID's result overrides that. */
    public record Entry(ReviewableItem item, VerificationStatus status, Instant submittedAt, boolean resubmitted, boolean awaitingProvider) {

        public Entry(ReviewableItem item, VerificationStatus status, Instant submittedAt, boolean resubmitted) {
            this(item, status, submittedAt, resubmitted, false);
        }

        /** "Driver's licence", "Vehicle registration"... - the document chip. */
        @com.fasterxml.jackson.annotation.JsonProperty("label")
        public String label() {
            return item == null ? null : item.label();
        }
    }
}
