package org.coryd.ops.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.coryd.common.enums.ReviewableItem;
import org.coryd.common.enums.VerificationStatus;

public record ItemDecision(
        @NotNull ReviewableItem item,
        @NotNull VerificationStatus status,
        @Size(max = 255) String rejectionReason
) {
}
