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
    /**
     * awaitingProvider: still out with Smile ID - it can be decided now, and Smile ID's result overrides that when it comes.
     * The per-document actions: canApproveItem (with approveItemBlockedReason when not, and approveItemNeedsNote when the
     * approval overrides Smile ID's failed check or a rejection), canRequestResubmission (with resubmissionBlockedReason).
     * providerOutcome: Smile ID's last word on the licence when it didn't approve it (NAME_MISMATCH, FACE_MISMATCH...),
     * null otherwise and for the other documents. lockedBySmileId: a licence Smile ID approved - it stays approved.
     */
    public record ItemReview(ReviewableItem item, String label, VerificationStatus status, Instant submittedAt, boolean resubmitted,
                             ReviewMark mark, String note, String markedBy, Instant markedAt, boolean awaitingProvider,
                             boolean canApproveItem, String approveItemBlockedReason, boolean approveItemNeedsNote,
                             boolean canRequestResubmission, String resubmissionBlockedReason, String providerOutcome,
                             boolean lockedBySmileId) {
    }
}
