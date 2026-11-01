package org.coryd.profile.dto;

import org.coryd.common.enums.ReviewMark;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.VerificationStatus;

import java.time.Instant;
import java.util.List;

/**
 * Where an ops review stands: each item's status and the reviewer's mark on it (null = Unreviewed), and whether
 * Approve Driver / Reject / Save Changes can be pressed - with the reason when Approve can't, to show beside the button.
 * Approved documents can be marked too: flagging one and saving takes the approval back.
 */
public record VerificationReviewResponse(
        List<ItemReview> items,
        boolean canApprove,
        String approveBlockedReason,
        boolean canReject,
        /** Save Changes (Drivers Directory) would change something: a flag, or Verified on a document waiting for ops. */
        boolean canSave
) {
    /** awaitingProvider: still out with Smile ID - it can be decided now, and Smile ID's result overrides that when it comes. */
    public record ItemReview(ReviewableItem item, String label, VerificationStatus status, Instant submittedAt, boolean resubmitted,
                             ReviewMark mark, String note, String markedBy, Instant markedAt, boolean awaitingProvider) {
    }
}
