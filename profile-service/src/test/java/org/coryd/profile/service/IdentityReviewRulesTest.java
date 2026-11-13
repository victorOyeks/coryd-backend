package org.coryd.profile.service;

import org.coryd.common.enums.IdentityReviewSort;
import org.coryd.common.enums.IdentityReviewTab;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.SmileIdRetryReason;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

/** The identity back office's rules on their own - which tab, and what ops may do from each state. */
class IdentityReviewRulesTest {

    @Test
    void eachStateLandsInOneTab() {
        SmileIdCheck failed = check(1, SmileIdRetryReason.FACE_MISMATCH);
        SmileIdCheck spent = check(5, SmileIdRetryReason.FACE_MISMATCH);

        assertThat(IdentityReviewService.tabOf(VerificationStatus.APPROVED, spent, 5, true, false)).isEqualTo(IdentityReviewTab.APPROVED);
        assertThat(IdentityReviewService.tabOf(VerificationStatus.IN_REVIEW, spent, 5, true, false)).isEqualTo(IdentityReviewTab.IN_REVIEW);
        assertThat(IdentityReviewService.tabOf(VerificationStatus.PENDING, spent, 5, true, false)).isEqualTo(IdentityReviewTab.LOCKED_OUT);
        assertThat(IdentityReviewService.tabOf(VerificationStatus.REJECTED, spent, 5, true, false)).isEqualTo(IdentityReviewTab.LOCKED_OUT);
        assertThat(IdentityReviewService.tabOf(VerificationStatus.PENDING, spent, 5, false, false)).as("sandbox").isEqualTo(IdentityReviewTab.FAILED);
        assertThat(IdentityReviewService.tabOf(VerificationStatus.PENDING, failed, 5, true, false)).isEqualTo(IdentityReviewTab.FAILED);
        assertThat(IdentityReviewService.tabOf(VerificationStatus.REJECTED, null, 5, true, false)).isEqualTo(IdentityReviewTab.FAILED);
        assertThat(IdentityReviewService.tabOf(VerificationStatus.PENDING, check(0, null), 5, true, false)).isEqualTo(IdentityReviewTab.PENDING);
        assertThat(IdentityReviewService.tabOf(VerificationStatus.PENDING, spent, 5, true, true))
                .as("a driver whose NIN is accepted waits on the licence").isEqualTo(IdentityReviewTab.PENDING);
    }

    @Test
    void smileIdApprovalsStayApprovedUnlessAPartialNameMatchIsStillToBeReviewed() {
        assertThat(IdentityReviewService.rejectBlockedReason(VerificationStatus.APPROVED, true, false)).startsWith("Smile ID verified");
        assertThat(IdentityReviewService.rejectBlockedReason(VerificationStatus.APPROVED, true, true)).isNull();
        assertThat(IdentityReviewService.rejectBlockedReason(VerificationStatus.APPROVED, false, false)).as("ops' own approval").isNull();
        assertThat(IdentityReviewService.rejectBlockedReason(VerificationStatus.REJECTED, false, false)).isNotNull();
        for (VerificationStatus open : new VerificationStatus[]{VerificationStatus.PENDING, VerificationStatus.IN_REVIEW}) {
            assertThat(IdentityReviewService.rejectBlockedReason(open, false, false)).isNull();
            assertThat(IdentityReviewService.approveBlockedReason(open, false)).isNull();
        }
        assertThat(IdentityReviewService.approveBlockedReason(VerificationStatus.REJECTED, false)).isNull();
        assertThat(IdentityReviewService.approveBlockedReason(VerificationStatus.APPROVED, false)).isNotNull();
    }

    @Test
    void whoCountsAsApprovedBySmileId() {
        assertThat(IdentityReviewService.approvedByProvider(verification(VerificationStatus.APPROVED, "SMILE_ID"))).isTrue();
        assertThat(IdentityReviewService.approvedByProvider(verification(VerificationStatus.APPROVED, DriverIdentity.DECIDED_BY))).isTrue();
        assertThat(IdentityReviewService.approvedByProvider(verification(VerificationStatus.APPROVED, "0b5e0000-ops"))).isFalse();
        assertThat(IdentityReviewService.approvedByProvider(verification(VerificationStatus.REJECTED, "SMILE_ID"))).isFalse();
    }

    @Test
    void approvingOverAFailureNeedsANote() {
        assertThat(IdentityReviewService.approveNeedsNote(IdentityReviewTab.FAILED)).isTrue();
        assertThat(IdentityReviewService.approveNeedsNote(IdentityReviewTab.LOCKED_OUT)).isTrue();
        assertThat(IdentityReviewService.approveNeedsNote(IdentityReviewTab.IN_REVIEW)).isFalse();
        assertThat(IdentityReviewService.approveNeedsNote(IdentityReviewTab.PENDING)).isFalse();
    }

    @Test
    void resetNeedsTriesUsedAndSomeoneNotYetVerified() {
        assertThat(IdentityReviewService.resetBlockedReason(VerificationStatus.PENDING, 5)).isNull();
        assertThat(IdentityReviewService.resetBlockedReason(VerificationStatus.PENDING, 0)).isNotNull();
        assertThat(IdentityReviewService.resetBlockedReason(VerificationStatus.APPROVED, 3)).isNotNull();
    }

    @Test
    void sortsAreWhitelistedColumnsWithMissingValuesLast() {
        assertThat(IdentityReviewService.orderBy(null, null)).isEqualTo("submitted_at ASC NULLS LAST, user_id ASC");
        assertThat(IdentityReviewService.orderBy(IdentityReviewSort.NAME, Sort.Direction.DESC)).isEqualTo("name_key DESC NULLS LAST, user_id ASC");
    }

    private static SmileIdCheck check(int attempts, SmileIdRetryReason retry) {
        return SmileIdCheck.builder().userId("u").checkType(SmileIdCheckType.NIN).attempts(attempts).retryReason(retry).build();
    }

    private static IdentityVerification verification(VerificationStatus status, String decidedBy) {
        return IdentityVerification.builder().userId("u").status(status).decidedBy(decidedBy).build();
    }
}
