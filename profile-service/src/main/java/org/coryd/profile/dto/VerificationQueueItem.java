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
    public record Entry(ReviewableItem item, VerificationStatus status, Instant submittedAt, boolean resubmitted) {
    }
}
