package org.coryd.profile.dto;

import jakarta.validation.constraints.NotNull;
import org.coryd.common.enums.VerificationStatus;

public record ItemDecision(
        @NotNull ReviewableItem item,
        @NotNull VerificationStatus status,
        String rejectionReason
) {
}
