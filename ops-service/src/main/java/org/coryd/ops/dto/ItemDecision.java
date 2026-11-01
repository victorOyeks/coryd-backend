package org.coryd.ops.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.VerificationStatus;

public record ItemDecision(
        @NotNull ReviewableItem item,
        @NotNull VerificationStatus status,
        @Size(max = 255) String rejectionReason,
        /** Optional note on an approval, kept in the decision history. */
        @Size(max = 500) String note
) {
    public ItemDecision(ReviewableItem item, VerificationStatus status, String rejectionReason) {
        this(item, status, rejectionReason, null);
    }
}
