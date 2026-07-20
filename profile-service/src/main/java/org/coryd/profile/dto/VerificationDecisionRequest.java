package org.coryd.profile.dto;

import jakarta.validation.constraints.NotNull;
import org.coryd.common.enums.VerificationStatus;

public record VerificationDecisionRequest(
        @NotNull VerificationStatus status,
        String rejectionReason,
        String reviewedBy
) {
}
