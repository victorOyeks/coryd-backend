package org.coryd.profile.service;

import org.coryd.common.enums.IdentityReviewTab;
import org.coryd.common.enums.RiderIdentityStatus;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.entity.SmileIdCheckType;
import org.coryd.profile.entity.SmileIdRetryReason;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Riders Directory's identity status: NOT_STARTED, else exactly the ops identity screen's tab. */
class RiderIdentityStatusesTest {

    private final IdentityVerificationRepository verifications = mock(IdentityVerificationRepository.class);
    private final SmileIdCheckRepository checks = mock(SmileIdCheckRepository.class);
    private final DriverProfileRepository drivers = mock(DriverProfileRepository.class);
    private final SmileIdProperties properties = new SmileIdProperties();
    private final RiderIdentityStatuses statuses = new RiderIdentityStatuses(verifications, checks, drivers, properties);

    @Test
    void everyTabHasTheSameNamedStatusAndNoCheckIsNotStarted() {
        for (IdentityReviewTab tab : IdentityReviewTab.values()) {
            assertThat(RiderIdentityStatus.of(tab).name()).isEqualTo(tab.name());
        }
        assertThat(RiderIdentityStatus.of(null)).isEqualTo(RiderIdentityStatus.NOT_STARTED);
        assertThat(RiderIdentityStatus.values()).hasSize(IdentityReviewTab.values().length + 1);
    }

    @Test
    void eachValueFromTheChecks() {
        SmileIdCheck retry = nin(1, SmileIdRetryReason.FACE_MISMATCH);
        SmileIdCheck spent = nin(SmileIdCheck.MAX_ATTEMPTS, SmileIdRetryReason.FACE_MISMATCH);
        SmileIdCheck lookupSpent = SmileIdCheck.builder().userId("u").checkType(SmileIdCheckType.NIN_LOOKUP)
                .attempts(properties.getNinLookupMaxAttempts()).build();

        assertThat(status(VerificationStatus.PENDING, null, false)).isEqualTo(RiderIdentityStatus.PENDING);
        assertThat(status(VerificationStatus.IN_REVIEW, spent, false)).isEqualTo(RiderIdentityStatus.IN_REVIEW);
        assertThat(status(VerificationStatus.REJECTED, null, false)).isEqualTo(RiderIdentityStatus.FAILED);
        assertThat(status(VerificationStatus.PENDING, retry, false)).isEqualTo(RiderIdentityStatus.FAILED);
        assertThat(status(VerificationStatus.APPROVED, spent, false)).isEqualTo(RiderIdentityStatus.APPROVED);
        assertThat(status(VerificationStatus.PENDING, spent, false)).isEqualTo(RiderIdentityStatus.LOCKED_OUT);
        assertThat(status(VerificationStatus.REJECTED, lookupSpent, false)).isEqualTo(RiderIdentityStatus.LOCKED_OUT);
        assertThat(status(VerificationStatus.PENDING, spent, true)).as("a driver whose NIN lookup is accepted").isEqualTo(RiderIdentityStatus.PENDING);
        assertThat(RiderIdentityStatuses.status(VerificationStatus.PENDING, spent, SmileIdCheck.MAX_ATTEMPTS, false, false))
                .as("sandbox doesn't ration tries").isEqualTo(RiderIdentityStatus.FAILED);
    }

    @Test
    void manyAtOnceWithTheNewestNinCheckAndADriversLookup() {
        when(verifications.findByUserIdIn(any())).thenReturn(List.of(
                IdentityVerification.builder().userId("locked").status(VerificationStatus.PENDING).build(),
                IdentityVerification.builder().userId("driver").status(VerificationStatus.PENDING).build()));
        SmileIdCheck oldSpent = nin(SmileIdCheck.MAX_ATTEMPTS, null);
        oldSpent.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        SmileIdCheck newer = nin(0, null);
        newer.setUpdatedAt(Instant.parse("2026-02-01T00:00:00Z"));
        SmileIdCheck lockedCheck = SmileIdCheck.builder().userId("locked").checkType(SmileIdCheckType.NIN).attempts(SmileIdCheck.MAX_ATTEMPTS).build();
        SmileIdCheck driverCheck = SmileIdCheck.builder().userId("driver").checkType(SmileIdCheckType.NIN_LOOKUP).attempts(9).build();
        when(checks.findByCheckTypeInAndUserIdIn(any(), any())).thenReturn(List.of(lockedCheck, driverCheck));
        when(drivers.findByUserIdIn(anyList())).thenReturn(List.of(
                DriverProfile.builder().userId("driver").ninStatus(VerificationStatus.APPROVED).build()));

        Map<String, RiderIdentityStatus> result = statuses.of(List.of("locked", "driver", "never"));

        assertThat(result).containsEntry("locked", RiderIdentityStatus.LOCKED_OUT)
                .containsEntry("driver", RiderIdentityStatus.PENDING)
                .containsEntry("never", RiderIdentityStatus.NOT_STARTED);
        assertThat(IdentityReviewService.newer(oldSpent, newer)).isSameAs(newer);
    }

    private RiderIdentityStatus status(VerificationStatus status, SmileIdCheck check, boolean ninAccepted) {
        return RiderIdentityStatuses.status(status, check, IdentityReviewService.maxAttempts(check, properties), true, ninAccepted);
    }

    private static SmileIdCheck nin(int attempts, SmileIdRetryReason reason) {
        return SmileIdCheck.builder().userId("u").checkType(SmileIdCheckType.NIN).attempts(attempts).retryReason(reason).build();
    }
}
