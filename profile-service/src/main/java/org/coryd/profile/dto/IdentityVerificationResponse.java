package org.coryd.profile.dto;

import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.IdentityVerification;

import java.time.Instant;

/** PENDING = not submitted yet, IN_REVIEW = being checked, APPROVED = may book, REJECTED = see rejectionReason and resubmit. */
public record IdentityVerificationResponse(
        String userId,
        VerificationStatus status,
        String ninLast4,
        Instant submittedAt,
        Instant decidedAt,
        String rejectionReason
) {
    public static IdentityVerificationResponse from(IdentityVerification v) {
        return new IdentityVerificationResponse(v.getUserId(), v.getStatus(), v.getNinLast4(), v.getSubmittedAt(),
                v.getDecidedAt(), v.getRejectionReason());
    }

    public static IdentityVerificationResponse notStarted(String userId) {
        return new IdentityVerificationResponse(userId, VerificationStatus.PENDING, null, null, null, null);
    }
}
