package org.coryd.profile.dto;

import org.coryd.common.enums.IdentityReviewTab;
import org.coryd.common.enums.VerificationCheckOutcome;
import org.coryd.common.enums.VerificationCheckType;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.SmileIdRetryReason;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * One person's identity (NIN) check for the ops screen: where it stands, every Smile ID result, the tries, what ops can
 * do now and why not, and the decision history. decidedBy is SMILE_ID (or SMILE_ID_NIN_LOOKUP_AND_LICENCE) for an
 * automatic decision, otherwise the ops admin's user id - the history has their name.
 */
public record IdentityReviewDetail(
        Person person,
        IdentityReviewTab tab,
        VerificationStatus status,
        String ninLast4,
        Instant submittedAt,
        Instant decidedAt,
        String decidedBy,
        String rejectionReason,
        String decisionNote,
        boolean providerOverridden,
        VerifiedRecord verifiedRecord,
        VerificationStatus driverNinStatus,
        Attempts attempts,
        String selfieUrl,
        List<Check> checks,
        Actions actions,
        List<VerificationDecisionHistoryItem> history
) {
    public record Person(String userId, String firstName, String lastName, String phone, String photoUrl, boolean rider, boolean driver) {
    }

    /** What NIMC holds, kept when the check was approved - null until then. */
    public record VerifiedRecord(String firstName, String middleName, String lastName, LocalDate dateOfBirth) {
    }

    /**
     * Smile ID tries on the current NIN check (checkType NIN, or NIN_LOOKUP for a driver): used of max, left, lockedOut once
     * none are left (never in the sandbox), retryReason while Smile ID is waiting for them to try again, checkInProgress
     * while a check is out with Smile ID. legacyUploads counts the older selfie uploads (limit 5).
     */
    public record Attempts(SmileIdCheckType checkType, int used, int max, int left, boolean lockedOut, SmileIdRetryReason retryReason,
                           boolean checkInProgress, Instant checkStartedAt, int legacyUploads) {
    }

    /**
     * One Smile ID result, newest first. checkId is ours - pass it to the images call. partialNameMatchToReview: approved on a
     * partial name match ops hasn't reviewed yet - the one approval ops can still reject. hasImages: Smile ID kept images for
     * the job (NIN + selfie checks; a NIN lookup has none).
     */
    public record Check(String checkId, VerificationCheckType type, VerificationCheckOutcome outcome, Double nameMatchScore,
                        String summary, String smileJobId, Instant checkedAt, boolean partialNameMatchToReview, Instant reviewedAt,
                        boolean hasImages) {
    }

    /**
     * What ops can do now. Each blocked reason is the exact 400 message the action would get. approveNeedsNote: approving
     * overrides Smile ID's failed check (or a rejection), so a note is required.
     */
    public record Actions(boolean canApprove, String approveBlockedReason, boolean approveNeedsNote,
                          boolean canReject, String rejectBlockedReason,
                          boolean canResetAttempts, String resetBlockedReason) {
    }
}
