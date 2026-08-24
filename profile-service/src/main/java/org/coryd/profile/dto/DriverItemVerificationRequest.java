package org.coryd.profile.dto;

import jakarta.validation.constraints.NotNull;
import org.coryd.common.enums.DriverProfileVerificationItem;
import org.coryd.common.enums.VerificationStatus;

public record DriverItemVerificationRequest(
        @NotNull DriverProfileVerificationItem item,
        @NotNull VerificationStatus status,
        String rejectionReason,
        String reviewedBy
) {
}
