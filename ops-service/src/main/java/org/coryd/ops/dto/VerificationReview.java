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
    /**
     * awaitingProvider: still out with Smile ID - it can be decided now, and Smile ID's result overrides that when it comes.
     * Per-document actions: canApproveItem (approveItemBlockedReason when not; approveItemNeedsNote when approving overrides
     * Smile ID's failed check or a rejection) and canRequestResubmission (resubmissionBlockedReason when not).
     * providerOutcome: Smile ID's last word on the licence when it didn't approve it. lockedBySmileId: a licence Smile ID
     * approved, which stays approved.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ItemReview(ReviewableItem item, String label, VerificationStatus status, Instant submittedAt, boolean resubmitted,
                             ReviewMark mark, String note, String markedBy, Instant markedAt, boolean awaitingProvider,
                             boolean canApproveItem, String approveItemBlockedReason, boolean approveItemNeedsNote,
                             boolean canRequestResubmission, String resubmissionBlockedReason, String providerOutcome,
                             boolean lockedBySmileId) {
    }
}
