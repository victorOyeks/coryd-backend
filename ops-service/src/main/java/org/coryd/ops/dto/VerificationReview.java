package org.coryd.ops.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.coryd.common.enums.ReviewMark;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.VerificationStatus;

import java.time.Instant;
import java.util.List;

/**
 * Where the review stands: each item's mark - VERIFIED, FLAGGED, or null for Unreviewed - with its note, and whether
 * Approve Driver / Reject can be pressed. approveBlockedReason says why Approve can't, to show beside the button.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VerificationReview(
        List<ItemReview> items,
        boolean canApprove,
        String approveBlockedReason,
        boolean canReject,
        /** Save Changes (Drivers Directory) would change something: a flag, or Verified on a document waiting for ops. */
        boolean canSave
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    /** awaitingProvider: still out with Smile ID - it can be decided now, and Smile ID's result overrides that when it comes. */
    public record ItemReview(ReviewableItem item, String label, VerificationStatus status, Instant submittedAt, boolean resubmitted,
                             ReviewMark mark, String note, String markedBy, Instant markedAt, boolean awaitingProvider) {
    }
}
