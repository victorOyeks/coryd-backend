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
    public record Entry(ReviewableItem item, VerificationStatus status, Instant submittedAt, boolean resubmitted) {
    }
}
