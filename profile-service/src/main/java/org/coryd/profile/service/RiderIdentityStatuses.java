package org.coryd.profile.service;

import lombok.RequiredArgsConstructor;
import org.coryd.common.enums.RiderIdentityStatus;
import org.coryd.common.enums.VerificationStatus;
import org.coryd.profile.config.SmileIdProperties;
import org.coryd.profile.entity.DriverProfile;
import org.coryd.profile.entity.IdentityVerification;
import org.coryd.profile.entity.SmileIdCheck;
import org.coryd.profile.repository.DriverProfileRepository;
import org.coryd.profile.repository.IdentityVerificationRepository;
import org.coryd.profile.repository.SmileIdCheckRepository;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Each person's identity status for the Riders Directory: NOT_STARTED without an identity check, otherwise the ops
 * identity screen's tab, by IdentityReviewService.tabOf - the one classification, never a second. Three queries for
 * any number of people.
 */
@Component
@RequiredArgsConstructor
public class RiderIdentityStatuses {

    private final IdentityVerificationRepository identityVerificationRepository;
    private final SmileIdCheckRepository smileIdCheckRepository;
    private final DriverProfileRepository driverProfileRepository;
    private final SmileIdProperties properties;

    public RiderIdentityStatus of(String userId) {
        return of(List.of(userId)).get(userId);
    }

    /** Every id in userIds is in the result. */
    public Map<String, RiderIdentityStatus> of(Collection<String> userIds) {
        List<String> ids = userIds.stream().distinct().toList();
        Map<String, RiderIdentityStatus> result = new HashMap<>();
        ids.forEach(id -> result.put(id, RiderIdentityStatus.NOT_STARTED));
        if (ids.isEmpty()) {
            return result;
        }
        List<IdentityVerification> verifications = identityVerificationRepository.findByUserIdIn(ids);
        if (verifications.isEmpty()) {
            return result;
        }
        List<String> started = verifications.stream().map(IdentityVerification::getUserId).toList();
        Map<String, SmileIdCheck> checks = smileIdCheckRepository.findByCheckTypeInAndUserIdIn(IdentityReviewService.NIN_CHECKS, started)
                .stream().collect(Collectors.toMap(SmileIdCheck::getUserId, Function.identity(), IdentityReviewService::newer));
        Map<String, DriverProfile> drivers = driverProfileRepository.findByUserIdIn(started).stream()
                .collect(Collectors.toMap(DriverProfile::getUserId, Function.identity(), (a, b) -> a));
        boolean rationed = !properties.isSandbox();
        for (IdentityVerification v : verifications) {
            SmileIdCheck check = checks.get(v.getUserId());
            result.put(v.getUserId(), status(v.getStatus(), check, IdentityReviewService.maxAttempts(check, properties), rationed,
                    IdentityReviewService.ninAccepted(drivers.get(v.getUserId()))));
        }
        return result;
    }

    static RiderIdentityStatus status(VerificationStatus status, SmileIdCheck check, int maxAttempts, boolean rationed, boolean ninAccepted) {
        return RiderIdentityStatus.of(IdentityReviewService.tabOf(status, check, maxAttempts, rationed, ninAccepted));
    }
}
