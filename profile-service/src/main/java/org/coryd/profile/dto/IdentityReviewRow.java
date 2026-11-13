package org.coryd.profile.dto;

import org.coryd.common.enums.IdentityReviewTab;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.SmileIdRetryReason;

import java.time.Instant;

/**
 * One person on the ops identity (NIN) list. status is the check's own status; tab is where the list files it.
 * attemptsUsed / attemptsLeft count the Smile ID tries on their current NIN check (NIN + selfie, or a driver's NIN
 * lookup). retryReason: why Smile ID asked them to try again, while they haven't. latestOutcome / latestSummary: Smile
 * ID's last result, null if none came back.
 */
public record IdentityReviewRow(
        String userId,
        String firstName,
        String lastName,
        String phone,
        String photoUrl,
        boolean rider,
        boolean driver,
        IdentityReviewTab tab,
        VerificationStatus status,
        String ninLast4,
        Instant submittedAt,
        Instant decidedAt,
        int attemptsUsed,
        int attemptsLeft,
        SmileIdRetryReason retryReason,
        boolean checkInProgress,
        VerificationCheckOutcome latestOutcome,
        String latestSummary
) {
}
