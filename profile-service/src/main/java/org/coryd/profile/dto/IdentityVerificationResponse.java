package org.coryd.profile.dto;

import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdRetryReason;

import java.time.Instant;

/**
 * PENDING = not done yet (checkInProgress: Smile ID is still checking; retryReason: the last check
 * failed and they should try again), IN_REVIEW = with ops, APPROVED = verified, REJECTED = see
 * rejectionReason and try again.
 */
public record IdentityVerificationResponse(
        String userId,
        VerificationStatus status,
        String ninLast4,
        Instant submittedAt,
        Instant decidedAt,
        String rejectionReason,
        boolean checkInProgress,
        SmileIdRetryReason retryReason,
        int attemptsLeft
) {
    /** check is their Smile ID NIN check, null if they've never started one. */
    public static IdentityVerificationResponse from(IdentityVerification v, SmileIdCheck check) {
        boolean pending = v.getStatus() == VerificationStatus.PENDING;
        boolean inProgress = pending && check != null && check.waitingForResult();
        return new IdentityVerificationResponse(v.getUserId(), v.getStatus(), v.getNinLast4(), v.getSubmittedAt(),
                v.getDecidedAt(), v.getRejectionReason(), inProgress,
                pending && !inProgress && check != null ? check.getRetryReason() : null,
                check == null ? SmileIdCheck.MAX_ATTEMPTS : check.attemptsLeft());
    }

    public static IdentityVerificationResponse notStarted(String userId) {
        return new IdentityVerificationResponse(userId, VerificationStatus.PENDING, null, null, null, null, false, null,
                SmileIdCheck.MAX_ATTEMPTS);
    }
}
